package com.ticketide.consumer;

import com.ticketide.config.RabbitMQConfig;
import com.ticketide.dto.message.TicketMessage;
import com.ticketide.service.EmailService;
import com.ticketide.service.RedisService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 工单创建通知消费者：手动 ACK + 幂等（同一工单的创建通知只处理一次）
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class TicketCreateConsumer {

    private static final String IDEMPOTENT_KEY_PREFIX = "mq:idempotent:create:";
    private static final long IDEMPOTENT_TTL_SECONDS = 24 * 60 * 60L;

    private final EmailService emailService;
    private final RedisService redisService;

    @RabbitListener(queues = RabbitMQConfig.TICKET_CREATE_QUEUE)
    public void handleTicketCreate(TicketMessage message, Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String idempotentKey = IDEMPOTENT_KEY_PREFIX + message.getTicketId();
        try {
            if (!redisService.setIfAbsent(idempotentKey, IDEMPOTENT_TTL_SECONDS)) {
                log.info("重复的工单创建消息，跳过处理: ticketId={}", message.getTicketId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            log.info("收到工单创建消息: {}", message);
            emailService.sendTicketCreatedEmail(message);
            redisService.setTicketCache(message.getTicketId(), message);
            redisService.incrementTicketCount("daily");

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 回滚幂等标记 + 进死信队列，避免消息既没处理成功又被判定为重复
            redisService.delete(idempotentKey);
            log.error("处理工单创建消息失败，消息进入死信队列: ticketId={}, 原因: {}",
                    message.getTicketId(), e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
