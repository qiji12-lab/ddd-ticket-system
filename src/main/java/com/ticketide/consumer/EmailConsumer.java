package com.ticketide.consumer;

import com.ticketide.config.RabbitMQConfig;
import com.ticketide.dto.message.TicketNotificationMessage;
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
 * 通知消费者：发送工单状态变更邮件。
 * <p>
 * 可靠性三件套：
 * 1) 手动 ACK —— 只有邮件真正发送成功才 ack，避免"消息已消费但邮件没发出去"；
 * 2) 死信队列 —— 处理失败时 nack(requeue=false)，消息进死信队列而不是无限重投打爆消费者；
 * 3) 幂等 —— 消费者可能因网络抖动收到重复消息，用 Redis 中的 messageId 标记去重，防止重复发邮件。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class EmailConsumer {

    private static final String IDEMPOTENT_KEY_PREFIX = "mq:idempotent:notification:";
    private static final long IDEMPOTENT_TTL_SECONDS = 24 * 60 * 60L;

    private final EmailService emailService;
    private final RedisService redisService;

    @RabbitListener(queues = RabbitMQConfig.TICKET_NOTIFICATION_QUEUE)
    public void handleTicketNotification(TicketNotificationMessage notification,
                                        Channel channel,
                                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String idempotentKey = IDEMPOTENT_KEY_PREFIX + idempotentId(notification);
        try {
            if (!redisService.setIfAbsent(idempotentKey, IDEMPOTENT_TTL_SECONDS)) {
                log.info("检测到重复消息，跳过邮件发送: messageId={}", notification.getMessageId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 调用邮件服务发送真实邮件（未配置SMTP时内部自动降级为日志模拟）
            emailService.sendStatusChangeEmail(notification);
            log.info("邮件已发送至: {}, 工单ID: {}", notification.getReceiverEmail(), notification.getTicketId());

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 回滚幂等标记，让消息从死信队列重投时仍能被处理，避免"发了但被当成重复消息丢掉"
            redisService.delete(idempotentKey);
            // requeue=false：交给死信队列，人工排查后可重新投递，避免无限重试打爆消费者
            log.error("处理通知消息失败，消息进入死信队列: ticketId={}, 原因: {}",
                    notification.getTicketId(), e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 幂等键：优先用消息ID；缺失时退化为"工单ID + 状态"，保证同一状态变更不会重复通知
     */
    private String idempotentId(TicketNotificationMessage notification) {
        return notification.getMessageId() != null
                ? notification.getMessageId()
                : notification.getTicketId() + ":" + notification.getStatus();
    }
}
