package com.ticketide.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisLockUtil {

    private final RedissonClient redissonClient;

    private static final String LOCK_PREFIX = "ticket:lock:";

    public boolean tryLock(Long ticketId, long waitTime, long leaseTime, TimeUnit unit) {
        String lockKey = LOCK_PREFIX + ticketId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean locked = lock.tryLock(waitTime, leaseTime, unit);
            if (locked) {
                log.info("获取分布式锁成功: {}", lockKey);
            } else {
                log.info("获取分布式锁失败: {}", lockKey);
            }
            return locked;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取分布式锁被中断: {}", lockKey);
            return false;
        }
    }

    /**
     * 加锁（看门狗 Watchdog 自动续期）——抢单等长业务场景推荐使用。
     * <p>
     * 与 tryLock(waitTime, leaseTime, unit) 的区别：这里不显式指定 leaseTime，
     * Redisson 会使用默认租期 30 秒，并由看门狗线程每隔 1/3 租期（10 秒）自动续期到 30 秒。
     * 好处：业务执行耗时超过租期时锁不会被提前释放（避免第二个客服在业务未提交时就抢到同一工单）；
     * 兜底：持有锁的 JVM 宕机后看门狗停止续期，锁最多在 30 秒后自动过期，不会造成死锁。
     * <p>
     * 注意：看门狗续期依赖 JVM 存活，因此绝不能把业务逻辑放到异步线程里执行后再释放锁。
     */
    public boolean tryLockWithWatchdog(Long ticketId, long waitTime, TimeUnit unit) {
        String lockKey = LOCK_PREFIX + ticketId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean locked = lock.tryLock(waitTime, unit);
            if (locked) {
                log.info("获取分布式锁成功(看门狗续期): {}", lockKey);
            } else {
                log.info("获取分布式锁失败: {}", lockKey);
            }
            return locked;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取分布式锁被中断: {}", lockKey);
            return false;
        }
    }

    public boolean tryLockWithWatchdog(Long ticketId) {
        return tryLockWithWatchdog(ticketId, 3, TimeUnit.SECONDS);
    }

    public boolean tryLock(Long ticketId, long leaseTime, TimeUnit unit) {
        return tryLock(ticketId, 0, leaseTime, unit);
    }

    public boolean tryLock(Long ticketId) {
        return tryLock(ticketId, 5, TimeUnit.SECONDS);
    }

    public boolean tryLock(Long ticketId, long seconds) {
        return tryLock(ticketId, seconds, TimeUnit.SECONDS);
    }

    public void unlock(Long ticketId) {
        String lockKey = LOCK_PREFIX + ticketId;
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("释放分布式锁: {}", lockKey);
        }
    }

    public void lock(Long ticketId, long leaseTime, TimeUnit unit) {
        String lockKey = LOCK_PREFIX + ticketId;
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock(leaseTime, unit);
        log.info("获取分布式锁(阻塞): {}", lockKey);
    }

    public void lock(Long ticketId) {
        lock(ticketId, 30, TimeUnit.SECONDS);
    }

    /**
     * 通用业务锁（不绑定工单ID）：用于定时任务多节点互斥等场景。
     * 加锁成功表示"当前节点抢到了本次任务执行权"，其他节点直接跳过，避免重复执行。
     */
    public boolean tryLock(String businessKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(businessKey);
        try {
            boolean locked = lock.tryLock(waitTime, leaseTime, unit);
            log.info("{} 获取业务锁: {}, 结果: {}", locked ? "成功" : "失败", businessKey, locked);
            return locked;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取业务锁被中断: {}", businessKey);
            return false;
        }
    }

    public void unlock(String businessKey) {
        RLock lock = redissonClient.getLock(businessKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("释放业务锁: {}", businessKey);
        }
    }
}
