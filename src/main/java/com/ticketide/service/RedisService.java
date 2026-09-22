package com.ticketide.service;

import com.ticketide.dto.message.TicketMessage;

public interface RedisService {

    /**
     * 幂等标记：key 不存在时写入并返回 true（首次处理），已存在返回 false（重复请求）
     *
     * @param ttlSeconds 标记过期时间，避免 key 无限增长
     */
    boolean setIfAbsent(String key, long ttlSeconds);

    /**
     * 删除幂等标记：消费失败时回滚标记，保证消息重投时还有机会被处理，不会永久丢失
     */
    void delete(String key);


    void setTicketCache(Long ticketId, TicketMessage message);

    TicketMessage getTicketCache(Long ticketId);

    void updateTicketCache(Long ticketId, TicketMessage message);

    void deleteTicketCache(Long ticketId);

    void incrementTicketCount(String period);

    void incrementCompletedCount(String period);

    Long getTicketCount(String period);

    Long getCompletedCount(String period);
}
