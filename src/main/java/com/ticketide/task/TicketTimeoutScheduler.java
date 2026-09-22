package com.ticketide.task;

import com.ticketide.exception.BusinessException;
import com.ticketide.service.TicketService;
import com.ticketide.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 工单超时定时任务。
 * <pre>
 * 1) 待接单超过 N 小时：发邮件提醒管理员及时指派；
 * 2) 待确认超过 M 小时：自动关闭工单并写入默认好评。
 * </pre>
 * 两个技术要点：
 * - 用 fixedDelay 而不是 fixedRate：上一轮跑完才开始计时，任务执行时间长时不会堆积；
 * - 多节点互斥：本地没有 XXL-JOB，用 Redisson 分布式锁达到同样效果——同一时刻只有一个节点真正执行扫描，
 * 其他节点抢不到锁直接跳过（waitTime=0，不空转等待）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketTimeoutScheduler {

    /**
     * 扫描任务的分布式锁 key
     */
    private static final String SCAN_LOCK_KEY = "ticket:schedule:timeout-scan";

    /**
     * 锁的兜底租期：即使节点崩溃，最多 5 分钟后其他节点也能接管执行
     */
    private static final long SCAN_LOCK_LEASE_SECONDS = 300;

    private final TicketService ticketService;
    private final RedisLockUtil redisLockUtil;

    @Value("${ticketide.pending-timeout-hours:2}")
    private int pendingTimeoutHours;

    @Value("${ticketide.confirm-timeout-hours:24}")
    private int confirmTimeoutHours;

    @Scheduled(fixedDelayString = "${ticketide.timeout-scan-interval-ms:600000}",
            initialDelayString = "${ticketide.timeout-scan-initial-delay-ms:60000}")
    public void scanTimeoutTickets() {
        if (!redisLockUtil.tryLock(SCAN_LOCK_KEY, 0, SCAN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
            log.debug("未获取到超时扫描锁，本节点跳过本轮执行");
            return;
        }
        try {
            int reminded = ticketService.remindPendingTimeoutTickets(pendingTimeoutHours);

            List<Long> timeoutTicketIds = ticketService.findPendingConfirmTimeoutTicketIds(confirmTimeoutHours);
            int closed = 0;
            for (Long ticketId : timeoutTicketIds) {
                try {
                    ticketService.autoCloseTimeoutTicket(ticketId);
                    closed++;
                } catch (BusinessException e) {
                    // 工单可能刚好被客户手动确认或重开，跳过即可，不影响其他工单
                    log.info("跳过自动关闭 ticketId={}: {}", ticketId, e.getMessage());
                }
            }

            if (reminded > 0 || closed > 0) {
                log.info("工单超时扫描完成: 待接单提醒={}, 待确认自动关闭={}", reminded, closed);
            }
        } catch (Exception e) {
            log.error("工单超时扫描执行异常: {}", e.getMessage(), e);
        } finally {
            redisLockUtil.unlock(SCAN_LOCK_KEY);
        }
    }
}
