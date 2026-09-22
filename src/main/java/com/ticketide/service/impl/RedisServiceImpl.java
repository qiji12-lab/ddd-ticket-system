package com.ticketide.service.impl;

import com.ticketide.dto.message.TicketMessage;
import com.ticketide.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisServiceImpl implements RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String TICKET_CACHE_PREFIX = "ticket:info:";
    private static final String TICKET_COUNT_PREFIX = "ticket:count:";
    private static final String COMPLETED_COUNT_PREFIX = "ticket:completed:";

    @Override
    public boolean setIfAbsent(String key, long ttlSeconds) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "1", ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public void setTicketCache(Long ticketId, TicketMessage message) {
        String key = TICKET_CACHE_PREFIX + ticketId;
        redisTemplate.opsForValue().set(key, message, 30, TimeUnit.MINUTES);
        log.info("设置工单缓存: {}", key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public TicketMessage getTicketCache(Long ticketId) {
        String key = TICKET_CACHE_PREFIX + ticketId;
        Object result = redisTemplate.opsForValue().get(key);
        return result != null ? (TicketMessage) result : null;
    }

    @Override
    public void updateTicketCache(Long ticketId, TicketMessage message) {
        String key = TICKET_CACHE_PREFIX + ticketId;
        redisTemplate.opsForValue().set(key, message, 30, TimeUnit.MINUTES);
        log.info("更新工单缓存: {}", key);
    }

    @Override
    public void deleteTicketCache(Long ticketId) {
        String key = TICKET_CACHE_PREFIX + ticketId;
        redisTemplate.delete(key);
        log.info("删除工单缓存: {}", key);
    }

    @Override
    public void incrementTicketCount(String period) {
        String key = buildCountKey(TICKET_COUNT_PREFIX, period);
        redisTemplate.opsForValue().increment(key);
        if ("daily".equals(period)) {
            redisTemplate.expire(key, 1, TimeUnit.DAYS);
        }
        log.info("增加工单统计: {}", key);
    }

    @Override
    public void incrementCompletedCount(String period) {
        String key = buildCountKey(COMPLETED_COUNT_PREFIX, period);
        redisTemplate.opsForValue().increment(key);
        if ("daily".equals(period)) {
            redisTemplate.expire(key, 1, TimeUnit.DAYS);
        }
        log.info("增加完成统计: {}", key);
    }

    @Override
    public Long getTicketCount(String period) {
        String key = buildCountKey(TICKET_COUNT_PREFIX, period);
        Object result = redisTemplate.opsForValue().get(key);
        return result != null ? Long.parseLong(result.toString()) : 0L;
    }

    @Override
    public Long getCompletedCount(String period) {
        String key = buildCountKey(COMPLETED_COUNT_PREFIX, period);
        Object result = redisTemplate.opsForValue().get(key);
        return result != null ? Long.parseLong(result.toString()) : 0L;
    }

    private String buildCountKey(String prefix, String period) {
        if ("daily".equals(period)) {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            return prefix + date;
        }
        return prefix + period;
    }
}