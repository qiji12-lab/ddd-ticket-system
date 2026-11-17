package com.ticketide.service.impl;

import com.ticketide.dto.message.TicketNotificationMessage;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 并发抢单测试：验证分布式锁防止超卖，以及异常时锁在 finally 中释放。
 * 对应测试用例：TC-201 并发抢单压测、TC-203 锁释放异常测试
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConcurrentGrabTicketTest {

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

    private Ticket buildPendingTicket(Long id) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTitle("并发抢单测试工单");
        ticket.setStatus(TicketStatus.PENDING.getCode());
        ticket.setPriority(TicketPriority.HIGH.getCode());
        ticket.setCustomerId(4L);
        ticket.setAgentId(null);
        ticket.setVersion(0);
        return ticket;
    }

    private User buildAgent(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("agent" + id);
        user.setRole(UserRole.AGENT.getCode());
        user.setEmail("agent" + id + "@ticketide.com");
        return user;
    }

    // ==================== TC-201 并发抢单压测 ====================

    @Test
    @DisplayName("TC-201 并发抢单：10个线程同时抢单，仅1个成功，其余9个失败")
    void shouldOnlyAllowOneSuccessInConcurrentGrab() throws Exception {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 用 AtomicBoolean 模拟分布式锁：第一个线程 CAS 成功拿到锁，其余失败
        AtomicBoolean lockAvailable = new AtomicBoolean(true);
        when(redisLockUtil.tryLockWithWatchdog(eq(1L), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> lockAvailable.compareAndSet(true, false));

        // 模拟 unlock 恢复锁（让后续线程能尝试，但在并发场景下它们已经快速失败了）
        doAnswer(inv -> {
            lockAvailable.set(true);
            return null;
        }).when(redisLockUtil).unlock(1L);

        // 只有持有锁的线程才会走到 selectById
        when(ticketMapper.selectById(1L)).thenReturn(buildPendingTicket(1L));
        when(userService.getUserById(anyLong())).thenAnswer(inv -> buildAgent(inv.getArgument(0)));
        when(userService.getUserById(4L)).thenAnswer(inv -> {
            User u = new User();
            u.setId(4L);
            u.setUsername("customer01");
            u.setRole(UserRole.CUSTOMER.getCode());
            u.setEmail("customer01@test.com");
            return u;
        });

        // CAS 条件更新：第一个线程返回1（成功），后续返回0（已被抢走）
        AtomicBoolean ticketAvailable = new AtomicBoolean(true);
        when(ticketMapper.grabIfPending(eq(1L), anyLong(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> ticketAvailable.compareAndSet(true, false) ? 1 : 0);

        for (int i = 0; i < threadCount; i++) {
            final long agentId = 10 + i;
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    ticketService.grabTicket(1L, agentId);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "所有线程应在10秒内完成");

        assertEquals(1, successCount.get(), "只有1个线程应该抢单成功");
        assertEquals(threadCount - 1, failCount.get(), "其余线程应该失败");
    }

    // ==================== TC-203 锁释放异常测试 ====================

    @Test
    @DisplayName("TC-203 锁释放异常测试：业务代码抛异常时，锁在finally中正确释放")
    void shouldReleaseLockEvenWhenBusinessThrowsException() {
        when(redisLockUtil.tryLockWithWatchdog(eq(1L), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        // selectById 抛异常模拟数据库故障
        when(ticketMapper.selectById(1L)).thenThrow(new RuntimeException("数据库连接断开"));

        // grabTicket 应该抛出 RuntimeException（而非静默吞掉）
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> ticketService.grabTicket(1L, 2L));

        // 关键验证：即使业务抛异常，finally 块中的 unlock 仍被执行
        verify(redisLockUtil).unlock(1L);
    }

    @Test
    @DisplayName("TC-203 锁释放异常测试：工单不存在时抛BusinessException，锁仍被释放")
    void shouldReleaseLockWhenTicketNotFound() {
        when(redisLockUtil.tryLockWithWatchdog(eq(2L), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(ticketMapper.selectById(2L)).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> ticketService.grabTicket(2L, 2L));

        verify(redisLockUtil).unlock(2L);
    }

    @Test
    @DisplayName("TC-203 锁释放异常测试：非待接单状态时抛BusinessException，锁仍被释放")
    void shouldReleaseLockWhenTicketNotPending() {
        Ticket processing = new Ticket();
        processing.setId(3L);
        processing.setStatus(TicketStatus.PROCESSING.getCode());
        processing.setCustomerId(4L);
        processing.setAgentId(2L);

        when(redisLockUtil.tryLockWithWatchdog(eq(3L), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(ticketMapper.selectById(3L)).thenReturn(processing);

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> ticketService.grabTicket(3L, 5L));

        verify(redisLockUtil).unlock(3L);
        // 确认没有执行 CAS 更新
        verify(ticketMapper, never()).grabIfPending(anyLong(), anyLong(), anyString(), anyString(), anyInt());
    }
}
