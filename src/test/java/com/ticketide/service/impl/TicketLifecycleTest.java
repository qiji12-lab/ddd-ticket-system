package com.ticketide.service.impl;

import com.ticketide.dto.message.TicketNotificationMessage;
import com.ticketide.dto.request.TicketCreateRequest;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单生命周期测试：创建→抢单→解决→确认关闭/驳回重开。
 * 对应测试用例：TC-003 创建工单、TC-005 客服处理（标记解决）、TC-006 用户确认关闭、TC-105 驳回重开
 * <p>
 * 测试策略：纯单元测试（不启动 Spring 容器），所有外部依赖（Mapper / MQ / Redis）均由 Mockito 接管，
 * 状态机本身也 Mock 掉——流转合法性由 {@code TicketStatusMachineTest} 专项覆盖，
 * 这里只验证"服务层在正确的时机、以正确的目标状态调用了状态机"这一编排职责。
 */
@ExtendWith(MockitoExtension.class)
class TicketLifecycleTest {

    @Mock private TicketMapper ticketMapper;
    @Mock private OperatorLogMapper operatorLogMapper;
    @Mock private UserService userService;
    @Mock private TicketStatusMachine statusMachine;
    @Mock private RedisLockUtil redisLockUtil;
    @Mock private AttachmentService attachmentService;
    @Mock private RatingService ratingService;
    @Mock private RedisService redisService;
    @Mock private RabbitMQService rabbitMQService;

    private TicketServiceImpl ticketService;

    @BeforeEach
    void setUp() {
        ticketService = new TicketServiceImpl(ticketMapper, operatorLogMapper, userService,
                statusMachine, redisLockUtil, attachmentService, ratingService, redisService);
        ticketService.setRabbitMQService(Optional.of(rabbitMQService));
    }

    // ==================== 测试数据工厂（Test Data Factory） ====================

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

    private TicketCreateRequest buildCreateRequest() {
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("登录页面无法打开");
        request.setDescription("用户反馈登录页面502");
        request.setPriority("HIGH");
        request.setCategory("TECHNICAL");
        return request;
    }

    // ==================== TC-003 创建工单 ====================

    @Nested
    @DisplayName("TC-003 创建工单")
    class CreateTicket {

        @Test
        @DisplayName("创建成功：状态为PENDING，绑定创建人为客户")
        void should_createPendingTicket_when_customerCreates() {
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(ticketMapper.insert(any(Ticket.class))).thenAnswer(invocation -> {
                Ticket t = invocation.getArgument(0);
                t.setId(1L);
                return 1;
            });

            Ticket result = ticketService.createTicket(buildCreateRequest(), 4L);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(TicketStatus.PENDING.getCode());
            assertThat(result.getCustomerId()).isEqualTo(4L);
            assertThat(result.getPriority()).isEqualTo("HIGH");
            assertThat(result.getCategory()).isEqualTo("TECHNICAL");
        }

        @Test
        @DisplayName("创建人不存在时拒绝")
        void should_throwBusinessException_when_creatorNotFound() {
            when(userService.getUserById(99L)).thenReturn(null);

            assertThatThrownBy(() -> ticketService.createTicket(buildCreateRequest(), 99L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("创建人不存在");

            verify(ticketMapper, never()).insert(any());
        }
    }

    // ==================== TC-005 客服处理（标记解决） ====================

    @Nested
    @DisplayName("TC-005 客服标记解决")
    class ResolveTicket {

        @Test
        @DisplayName("解决成功：PROCESSING→RESOLVED→PENDING_CONFIRM，并通知客户")
        void should_resolveAndNotifyCustomer_when_agentResolvesOwnTicket() {
            Ticket processing = buildTicket(1L, TicketStatus.PROCESSING, 4L, 2L);
            when(ticketMapper.selectById(1L)).thenReturn(processing);
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(ticketMapper.updateById(processing)).thenReturn(1);

            Ticket result = ticketService.resolveTicket(1L, 2L);

            assertThat(result).isNotNull();
            // 状态机被调用两次：PROCESSING→RESOLVED, RESOLVED→PENDING_CONFIRM
            verify(statusMachine).changeStatus(processing, TicketStatus.RESOLVED);
            verify(statusMachine).changeStatus(processing, TicketStatus.PENDING_CONFIRM);
            // 通知客户
            verify(rabbitMQService).sendNotification(any(TicketNotificationMessage.class));
        }

        @Test
        @DisplayName("非经办客服不能解决他人工单")
        void should_throwBusinessException_when_agentResolvesOthersTicket() {
            when(ticketMapper.selectById(2L)).thenReturn(buildTicket(2L, TicketStatus.PROCESSING, 4L, 2L));
            when(userService.getUserById(3L)).thenReturn(buildUser(3L, "agent02", UserRole.AGENT));

            assertThatThrownBy(() -> ticketService.resolveTicket(2L, 3L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能解决自己负责的工单");

            verify(statusMachine, never()).changeStatus(any(), any());
        }

        @Test
        @DisplayName("工单不存在时拒绝解决")
        void should_throwBusinessException_when_resolveTicketNotFound() {
            when(ticketMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> ticketService.resolveTicket(99L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("工单不存在");

            verify(statusMachine, never()).changeStatus(any(), any());
        }

        @Test
        @DisplayName("操作人不存在时拒绝解决")
        void should_throwBusinessException_when_resolveOperatorNotFound() {
            when(ticketMapper.selectById(1L)).thenReturn(buildTicket(1L, TicketStatus.PROCESSING, 4L, 2L));
            when(userService.getUserById(99L)).thenReturn(null);

            assertThatThrownBy(() -> ticketService.resolveTicket(1L, 99L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户不存在");

            verify(statusMachine, never()).changeStatus(any(), any());
        }

        @Test
        @DisplayName("乐观锁冲突时抛出提示刷新重试的异常")
        void should_throwBusinessException_when_resolveOptimisticLockConflict() {
            Ticket processing = buildTicket(1L, TicketStatus.PROCESSING, 4L, 2L);
            when(ticketMapper.selectById(1L)).thenReturn(processing);
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));
            // 版本号不匹配，updateById 返回 0 行
            when(ticketMapper.updateById(processing)).thenReturn(0);

            assertThatThrownBy(() -> ticketService.resolveTicket(1L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("工单已被他人修改，请刷新后重试");
        }

        @Test
        @DisplayName("RabbitMQ未启用时降级为日志，解决动作仍成功")
        void should_resolveSuccessfullyWithoutNotification_when_rabbitMQDisabled() {
            // 模拟 MQ 未装配：全新实例不调用 setRabbitMQService，rabbitMQService 字段保持 null。
            // 注意不能复用 setUp 中的实例——该实例已注入 mock，Optional.empty() 的 ifPresent 不会将其清除
            TicketServiceImpl serviceWithoutMQ = new TicketServiceImpl(ticketMapper, operatorLogMapper, userService,
                    statusMachine, redisLockUtil, attachmentService, ratingService, redisService);

            Ticket processing = buildTicket(1L, TicketStatus.PROCESSING, 4L, 2L);
            when(ticketMapper.selectById(1L)).thenReturn(processing);
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(ticketMapper.updateById(processing)).thenReturn(1);

            Ticket result = serviceWithoutMQ.resolveTicket(1L, 2L);

            assertThat(result).isNotNull();
            verify(statusMachine).changeStatus(processing, TicketStatus.RESOLVED);
            // MQ 未启用时内部直接跳过发送，不抛异常
            verify(rabbitMQService, never()).sendNotification(any());
        }

        @Test
        @DisplayName("管理员可以解决任何工单")
        void should_resolveAnyTicket_when_operatorIsAdmin() {
            Ticket processing = buildTicket(3L, TicketStatus.PROCESSING, 4L, 2L);
            when(ticketMapper.selectById(3L)).thenReturn(processing);
            when(userService.getUserById(1L)).thenReturn(buildUser(1L, "admin", UserRole.ADMIN));
            when(ticketMapper.updateById(processing)).thenReturn(1);

            Ticket result = ticketService.resolveTicket(3L, 1L);

            assertThat(result).isNotNull();
            verify(statusMachine).changeStatus(processing, TicketStatus.RESOLVED);
        }
    }

    // ==================== TC-006 用户确认关闭 ====================

    @Nested
    @DisplayName("TC-006 用户确认关闭")
    class CloseTicket {

        @Test
        @DisplayName("客户确认关闭自己的待确认工单")
        void should_closeTicket_when_customerConfirmsOwnTicket() {
            Ticket pendingConfirm = buildTicket(1L, TicketStatus.PENDING_CONFIRM, 4L, 2L);
            when(ticketMapper.selectById(1L)).thenReturn(pendingConfirm);
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(ticketMapper.updateById(pendingConfirm)).thenReturn(1);

            Ticket result = ticketService.closeTicket(1L, 4L);

            assertThat(result).isNotNull();
            verify(statusMachine).changeStatus(pendingConfirm, TicketStatus.CLOSED);
        }

        @Test
        @DisplayName("客户不能关闭他人创建的工单")
        void should_throwBusinessException_when_customerClosesOthersTicket() {
            when(ticketMapper.selectById(2L)).thenReturn(buildTicket(2L, TicketStatus.PENDING_CONFIRM, 4L, 2L));
            when(userService.getUserById(5L)).thenReturn(buildUser(5L, "customer02", UserRole.CUSTOMER));

            assertThatThrownBy(() -> ticketService.closeTicket(2L, 5L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能关闭自己创建的工单");

            verify(statusMachine, never()).changeStatus(any(), any());
        }

        @Test
        @DisplayName("工单不存在时拒绝关闭")
        void should_throwBusinessException_when_closeTicketNotFound() {
            when(ticketMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> ticketService.closeTicket(99L, 4L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("工单不存在");

            verify(statusMachine, never()).changeStatus(any(), any());
        }

        @Test
        @DisplayName("乐观锁冲突时拒绝关闭并提示刷新")
        void should_throwBusinessException_when_closeOptimisticLockConflict() {
            Ticket pendingConfirm = buildTicket(1L, TicketStatus.PENDING_CONFIRM, 4L, 2L);
            when(ticketMapper.selectById(1L)).thenReturn(pendingConfirm);
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(ticketMapper.updateById(pendingConfirm)).thenReturn(0);

            assertThatThrownBy(() -> ticketService.closeTicket(1L, 4L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("工单已被他人修改，请刷新后重试");
        }

        @Test
        @DisplayName("管理员可以关闭任何工单")
        void should_closeAnyTicket_when_operatorIsAdmin() {
            Ticket pendingConfirm = buildTicket(3L, TicketStatus.PENDING_CONFIRM, 4L, 2L);
            when(ticketMapper.selectById(3L)).thenReturn(pendingConfirm);
            when(userService.getUserById(1L)).thenReturn(buildUser(1L, "admin", UserRole.ADMIN));
            when(ticketMapper.updateById(pendingConfirm)).thenReturn(1);

            Ticket result = ticketService.closeTicket(3L, 1L);

            assertThat(result).isNotNull();
            verify(statusMachine).changeStatus(pendingConfirm, TicketStatus.CLOSED);
        }
    }

    // ==================== TC-105 驳回重开 ====================

    @Nested
    @DisplayName("TC-105 驳回重开")
    class ReopenTicket {

        @Test
        @DisplayName("客户驳回：PENDING_CONFIRM→REOPENED，通知经办客服")
        void should_reopenAndNotifyAgent_when_customerRejects() {
            Ticket pendingConfirm = buildTicket(1L, TicketStatus.PENDING_CONFIRM, 4L, 2L);
            when(ticketMapper.selectById(1L)).thenReturn(pendingConfirm);
            when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
            when(userService.getUserById(2L)).thenReturn(buildUser(2L, "agent01", UserRole.AGENT));
            when(ticketMapper.updateById(pendingConfirm)).thenReturn(1);

            Ticket result = ticketService.reopenTicket(1L, 4L);

            assertThat(result).isNotNull();
            verify(statusMachine).changeStatus(pendingConfirm, TicketStatus.REOPENED);
            verify(rabbitMQService).sendNotification(any(TicketNotificationMessage.class));
        }

        @Test
        @DisplayName("客户不能重开他人创建的工单")
        void should_throwBusinessException_when_customerReopensOthersTicket() {
            when(ticketMapper.selectById(2L)).thenReturn(buildTicket(2L, TicketStatus.PENDING_CONFIRM, 4L, 2L));
            when(userService.getUserById(5L)).thenReturn(buildUser(5L, "customer02", UserRole.CUSTOMER));

            assertThatThrownBy(() -> ticketService.reopenTicket(2L, 5L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能重新打开自己创建的工单");

            verify(statusMachine, never()).changeStatus(any(), any());
        }

        @Test
        @DisplayName("操作人不存在时拒绝重开")
        void should_throwBusinessException_when_reopenOperatorNotFound() {
            when(ticketMapper.selectById(1L)).thenReturn(buildTicket(1L, TicketStatus.PENDING_CONFIRM, 4L, 2L));
            when(userService.getUserById(99L)).thenReturn(null);

            assertThatThrownBy(() -> ticketService.reopenTicket(1L, 99L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户不存在");

            verify(statusMachine, never()).changeStatus(any(), any());
        }

        @Test
        @DisplayName("管理员可以重开任意工单")
        void should_reopenAnyTicket_when_operatorIsAdmin() {
            Ticket pendingConfirm = buildTicket(3L, TicketStatus.PENDING_CONFIRM, 4L, 2L);
            when(ticketMapper.selectById(3L)).thenReturn(pendingConfirm);
            when(userService.getUserById(1L)).thenReturn(buildUser(1L, "admin", UserRole.ADMIN));
            when(ticketMapper.updateById(pendingConfirm)).thenReturn(1);

            Ticket result = ticketService.reopenTicket(3L, 1L);

            assertThat(result).isNotNull();
            verify(statusMachine).changeStatus(pendingConfirm, TicketStatus.REOPENED);
        }

        @Test
        @DisplayName("工单不存在时拒绝重开")
        void should_throwBusinessException_when_reopenTicketNotFound() {
            when(ticketMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> ticketService.reopenTicket(99L, 4L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("工单不存在");

            verify(statusMachine, never()).changeStatus(any(), any());
        }
    }
}
