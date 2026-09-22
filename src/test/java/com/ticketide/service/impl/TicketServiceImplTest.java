package com.ticketide.service.impl;

import com.ticketide.dto.message.TicketNotificationMessage;
import com.ticketide.dto.request.TicketEscalateRequest;
import com.ticketide.dto.request.TicketStatusChangeRequest;
import com.ticketide.dto.request.TicketTransferRequest;
import com.ticketide.entity.Ticket;
import com.ticketide.entity.User;
import com.ticketide.enums.TicketPriority;
import com.ticketide.enums.TicketStatus;
import com.ticketide.enums.UserRole;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.OperatorLogMapper;
import com.ticketide.mapper.TicketMapper;
import com.ticketide.service.AttachmentService;
import com.ticketide.service.RabbitMQService;
import com.ticketide.service.RatingService;
import com.ticketide.service.RedisService;
import com.ticketide.service.UserService;
import com.ticketide.state.TicketStatusMachine;
import com.ticketide.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单服务单元测试：覆盖抢单并发控制、转交、升级、取消、超时自动关闭等主链路。
 * 说明：操作日志已由 @LogRecord + AOP 异步写入，因此这里不再校验日志写入，
 * 只校验业务状态与关键调用（日志由端到端验证覆盖）。
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private OperatorLogMapper operatorLogMapper;

    @Mock
    private UserService userService;

    @Mock
    private TicketStatusMachine statusMachine;

    @Mock
    private RedisLockUtil redisLockUtil;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private RatingService ratingService;

    @Mock
    private RedisService redisService;

    @Mock
    private RabbitMQService rabbitMQService;

    private TicketServiceImpl ticketService;

    @BeforeEach
    void setUp() {
        ticketService = new TicketServiceImpl(ticketMapper, operatorLogMapper, userService,
                statusMachine, redisLockUtil, attachmentService, ratingService, redisService);
        ticketService.setRabbitMQService(Optional.of(rabbitMQService));
    }

    private Ticket buildTicket(Long id, TicketStatus status, Long customerId, Long agentId) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTitle("测试工单");
        ticket.setStatus(status.getCode());
        ticket.setPriority(TicketPriority.MEDIUM.getCode());
        ticket.setCustomerId(customerId);
        ticket.setAgentId(agentId);
        ticket.setVersion(0);
        return ticket;
    }

    private User buildUser(Long id, String username, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role.getCode());
        user.setEmail(username + "@ticketide.com");
        return user;
    }

    // ==================== 抢单 ====================

    @Nested
    @DisplayName("抢单：Redisson 锁 + 条件更新防超卖")
    class GrabTicket {

        @Test
        @DisplayName("抢单成功：CAS 更新成功，绑定经办人并通知客户")
        void shouldGrabSuccessfully() {
            Ticket pending = buildTicket(1L, TicketStatus.PENDING, 4L, null);
            when(redisLockUtil.tryLockWithWatchdog(eq(1L), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(ticketMapper.selectById(1L)).thenReturn(pending);
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(ticketMapper.grabIfPending(1L, 2L, "PENDING", "PROCESSING", 0)).thenReturn(1);

            Ticket result = ticketService.grabTicket(1L, 2L);

            assertEquals("PROCESSING", result.getStatus());
            assertEquals(2L, result.getAgentId());
            assertEquals(1, result.getVersion());
            // 受理通知走 MQ 异步发送
            verify(rabbitMQService).sendNotification(any(TicketNotificationMessage.class));
            // 无论成功与否都必须释放锁
            verify(redisLockUtil).unlock(1L);
        }

        @Test
        @DisplayName("并发抢单：条件更新影响 0 行说明已被抢走，抛业务异常")
        void shouldFailWhenGrabbedByOthers() {
            Ticket pending = buildTicket(1L, TicketStatus.PENDING, 4L, null);
            when(redisLockUtil.tryLockWithWatchdog(eq(1L), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(ticketMapper.selectById(1L)).thenReturn(pending);
            when(userService.getUserById(9L)).thenReturn(buildUser(9L, "agent09", UserRole.AGENT));
            when(ticketMapper.grabIfPending(1L, 9L, "PENDING", "PROCESSING", 0)).thenReturn(0);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.grabTicket(1L, 9L));

            assertTrue(ex.getMessage().contains("抢走"), "异常信息应提示已被抢走，实际: " + ex.getMessage());
            verify(ticketMapper, never()).updateById(any());
            verify(rabbitMQService, never()).sendNotification(any());
            verify(redisLockUtil).unlock(1L);
        }

        @Test
        @DisplayName("非待接单状态不能抢单")
        void shouldRejectNonPendingTicket() {
            when(redisLockUtil.tryLockWithWatchdog(eq(1L), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(ticketMapper.selectById(1L)).thenReturn(buildTicket(1L, TicketStatus.PROCESSING, 4L, 3L));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.grabTicket(1L, 2L));

            assertTrue(ex.getMessage().contains("只有待接单状态"));
            verify(ticketMapper, never()).grabIfPending(anyLong(), anyLong(), anyString(), anyString(), anyInt());
            verify(redisLockUtil).unlock(1L);
        }

        @Test
        @DisplayName("未拿到分布式锁时快速失败，不查库也不释放锁")
        void shouldFailFastWhenLockNotAcquired() {
            when(redisLockUtil.tryLockWithWatchdog(eq(1L), anyLong(), any(TimeUnit.class))).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.grabTicket(1L, 2L));

            assertTrue(ex.getMessage().contains("正在被抢"));
            verify(ticketMapper, never()).selectById(anyLong());
            verify(redisLockUtil, never()).unlock(anyLong());
        }
    }

    // ==================== 转交 ====================

    @Nested
    @DisplayName("转交：只换经办人，不改状态")
    class TransferTicket {

        @Test
        @DisplayName("转交成功：经办人变更且状态保持处理中")
        void shouldTransferSuccessfully() {
            Ticket processing = buildTicket(5L, TicketStatus.PROCESSING, 4L, 2L);
            when(ticketMapper.selectById(5L)).thenReturn(processing);
            when(userService.getUserById(3L)).thenReturn(buildUser(3L, "agent02", UserRole.AGENT));
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));
            when(ticketMapper.updateById(processing)).thenReturn(1);

            TicketTransferRequest request = new TicketTransferRequest();
            request.setTargetAgentId(3L);
            request.setRemark("不熟悉该模块");

            Ticket result = ticketService.transferTicket(5L, request, 2L);

            assertEquals(3L, result.getAgentId());
            assertEquals("PROCESSING", result.getStatus(), "转交不应改变工单状态");
        }

        @Test
        @DisplayName("只能转交给客服角色")
        void shouldRejectTransferToNonAgent() {
            when(ticketMapper.selectById(5L)).thenReturn(buildTicket(5L, TicketStatus.PROCESSING, 4L, 2L));
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));

            TicketTransferRequest request = new TicketTransferRequest();
            request.setTargetAgentId(4L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.transferTicket(5L, request, 2L));

            assertTrue(ex.getMessage().contains("客服"));
            verify(ticketMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("客服只能转交自己负责的工单")
        void shouldRejectTransferByOtherAgent() {
            when(ticketMapper.selectById(7L)).thenReturn(buildTicket(7L, TicketStatus.PROCESSING, 4L, 2L));
            when(userService.getUserById(3L)).thenReturn(buildUser(3L, "agent02", UserRole.AGENT));
            when(userService.getUserById(8L)).thenReturn(buildUser(8L, "agent03", UserRole.AGENT));

            TicketTransferRequest request = new TicketTransferRequest();
            request.setTargetAgentId(3L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.transferTicket(7L, request, 8L));

            assertTrue(ex.getMessage().contains("只能转交自己负责的工单"));
        }

        @Test
        @DisplayName("非在办状态（已关闭）不可转交")
        void shouldRejectTransferOnClosedTicket() {
            when(ticketMapper.selectById(6L)).thenReturn(buildTicket(6L, TicketStatus.CLOSED, 4L, 2L));
            when(userService.getUserById(3L)).thenReturn(buildUser(3L, "agent02", UserRole.AGENT));
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));

            TicketTransferRequest request = new TicketTransferRequest();
            request.setTargetAgentId(3L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.transferTicket(6L, request, 2L));

            assertTrue(ex.getMessage().contains("不允许转交"));
            verify(ticketMapper, never()).updateById(any());
        }
    }

    // ==================== 升级 ====================

    @Nested
    @DisplayName("升级：提升为高优先级并通知主管")
    class EscalateTicket {

        @Test
        @DisplayName("升级成功：优先级改 HIGH 并异步通知主管")
        void shouldEscalateAndNotifySupervisors() {
            Ticket processing = buildTicket(9L, TicketStatus.PROCESSING, 4L, 2L);
            processing.setPriority(TicketPriority.LOW.getCode());
            when(ticketMapper.selectById(9L)).thenReturn(processing);
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));
            when(userService.getUsersByRole(UserRole.ADMIN.getCode()))
                    .thenReturn(List.of(buildUser(1L, "admin", UserRole.ADMIN)));
            when(ticketMapper.updateById(processing)).thenReturn(1);

            TicketEscalateRequest request = new TicketEscalateRequest();
            request.setRemark("客户要求今天内处理");

            Ticket result = ticketService.escalateTicket(9L, request, 2L);

            assertEquals("HIGH", result.getPriority());
            verify(rabbitMQService).sendNotification(any(TicketNotificationMessage.class));
        }

        @Test
        @DisplayName("已是高优先级的工单不可重复升级")
        void shouldRejectEscalateWhenAlreadyHigh() {
            Ticket processing = buildTicket(9L, TicketStatus.PROCESSING, 4L, 2L);
            processing.setPriority(TicketPriority.HIGH.getCode());
            when(ticketMapper.selectById(9L)).thenReturn(processing);
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.escalateTicket(9L, new TicketEscalateRequest(), 2L));

            assertTrue(ex.getMessage().contains("无需重复升级"));
            verify(rabbitMQService, never()).sendNotification(any());
        }
    }

    // ==================== 取消 ====================

    @Nested
    @DisplayName("取消：仅创建人本人或管理员")
    class CancelTicket {

        @Test
        @DisplayName("客户取消自己创建的工单：走状态机流转为 CANCELLED")
        void shouldCancelOwnTicket() {
            Ticket pending = buildTicket(11L, TicketStatus.PENDING, 4L, null);
            when(ticketMapper.selectById(11L)).thenReturn(pending);
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(ticketMapper.updateById(pending)).thenReturn(1);

            Ticket result = ticketService.cancelTicket(11L, 4L);

            assertNotNull(result);
            verify(statusMachine).changeStatus(pending, TicketStatus.CANCELLED);
            verify(ticketMapper).updateById(pending);
        }

        @Test
        @DisplayName("不能取消他人的工单")
        void shouldRejectCancelOthersTicket() {
            when(ticketMapper.selectById(12L)).thenReturn(buildTicket(12L, TicketStatus.PENDING, 4L, null));
            when(userService.getUserById(5L)).thenReturn(buildUser(5L, "customer02", UserRole.CUSTOMER));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.cancelTicket(12L, 5L));

            assertTrue(ex.getMessage().contains("只能取消自己创建的工单"));
            verify(statusMachine, never()).changeStatus(any(), any());
        }

        @Test
        @DisplayName("乐观锁冲突：更新 0 行时提示刷新重试，避免静默失败")
        void shouldReportOptimisticLockConflict() {
            Ticket pending = buildTicket(13L, TicketStatus.PENDING, 4L, null);
            when(ticketMapper.selectById(13L)).thenReturn(pending);
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(ticketMapper.updateById(pending)).thenReturn(0);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.cancelTicket(13L, 4L));

            assertTrue(ex.getMessage().contains("刷新"));
        }

        @Test
        @DisplayName("通用状态接口不能用于取消工单，防止绕过权限校验")
        void shouldRejectCancelThroughGenericStatusApi() {
            when(ticketMapper.selectById(14L)).thenReturn(buildTicket(14L, TicketStatus.PENDING, 4L, null));

            TicketStatusChangeRequest request = new TicketStatusChangeRequest();
            request.setTargetStatus(TicketStatus.CANCELLED.getCode());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ticketService.updateTicketStatus(14L, request, 4L));

            assertTrue(ex.getMessage().contains("取消工单接口"));
            verify(statusMachine, never()).changeStatus(any(), any());
        }
    }

    // ==================== 超时任务 ====================

    @Nested
    @DisplayName("超时处理：提醒与自动关闭")
    class TimeoutProcess {

        @Test
        @DisplayName("待接单超时：给主管发提醒，同一工单只提醒一次")
        void shouldRemindAdminsOnlyOnce() {
            Ticket timeoutTicket = buildTicket(21L, TicketStatus.PENDING, 4L, null);
            when(ticketMapper.selectPendingTimeoutTickets(eq("PENDING"), any(), anyInt()))
                    .thenReturn(List.of(timeoutTicket));
            when(redisService.setIfAbsent(anyString(), anyLong())).thenReturn(true);
            when(userService.getUsersByRole(UserRole.ADMIN.getCode()))
                    .thenReturn(List.of(buildUser(1L, "admin", UserRole.ADMIN)));

            int reminded = ticketService.remindPendingTimeoutTickets(2);

            assertEquals(1, reminded);
            verify(rabbitMQService).sendNotification(any(TicketNotificationMessage.class));
        }

        @Test
        @DisplayName("待接单超时：已提醒过的工单跳过，避免重复打扰")
        void shouldSkipAlreadyRemindedTicket() {
            when(ticketMapper.selectPendingTimeoutTickets(eq("PENDING"), any(), anyInt()))
                    .thenReturn(List.of(buildTicket(22L, TicketStatus.PENDING, 4L, null)));
            when(redisService.setIfAbsent(anyString(), anyLong())).thenReturn(false);

            int reminded = ticketService.remindPendingTimeoutTickets(2);

            assertEquals(0, reminded);
            verify(rabbitMQService, never()).sendNotification(any());
        }

        @Test
        @DisplayName("待确认超时：自动关闭并写入默认好评")
        void shouldAutoCloseAndRateByDefault() {
            Ticket pendingConfirm = buildTicket(23L, TicketStatus.PENDING_CONFIRM, 4L, 2L);
            when(ticketMapper.selectById(23L)).thenReturn(pendingConfirm);
            when(ticketMapper.updateById(pendingConfirm)).thenReturn(1);

            Ticket result = ticketService.autoCloseTimeoutTicket(23L);

            assertNotNull(result);
            verify(statusMachine).changeStatus(pendingConfirm, TicketStatus.CLOSED);
            verify(ratingService).rateByDefault(eq(23L), anyString());
        }
    }
}
