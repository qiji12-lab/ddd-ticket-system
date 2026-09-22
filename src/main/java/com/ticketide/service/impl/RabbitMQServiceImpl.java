package com.ticketide.service.impl;

import com.ticketide.config.RabbitMQConfig;
import com.ticketide.dto.message.TicketNotificationMessage;
import com.ticketide.service.RabbitMQService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMQServiceImpl implements RabbitMQService {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void sendNotification(TicketNotificationMessage message) {
        if (message.getMessageId() == null) {
            // 每条消息带唯一ID，消费端用它做幂等去重
            message.setMessageId(UUID.randomUUID().toString());
        }
        CorrelationData correlationData = new CorrelationData(message.getMessageId());

        try {
            // 直接发送对象，由 Jackson2JsonMessageConverter 统一序列化；
            // MessagePostProcessor 中设置 deliveryMode=PERSISTENT，让消息落盘，broker 重启也不丢
            rabbitTemplate.convertAndSend(RabbitMQConfig.TICKET_FANOUT_EXCHANGE, "", message, amqpMessage -> {
                amqpMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                amqpMessage.getMessageProperties().setMessageId(message.getMessageId());
                return amqpMessage;
            }, correlationData);

            // 生产者确认（publisher-confirm-type=correlated）：异步回调里检查消息是否真的到达交换机
            correlationData.getFuture().whenComplete((confirm, ex) -> {
                if (ex != null) {
                    log.error("消息确认异常，可能丢失: messageId={}, 原因: {}", message.getMessageId(), ex.getMessage());
                } else if (confirm == null || !confirm.isAck()) {
                    log.error("消息未被交换机确认（nack）: messageId={}, reason={}",
                            message.getMessageId(), confirm == null ? "无确认结果" : confirm.getReason());
                }
            });

            log.info("发送工单通知消息: ticketId={}, receiver={}, messageId={}",
                    message.getTicketId(), message.getReceiverEmail(), message.getMessageId());
        } catch (AmqpException e) {
            // MQ 属于可选组件（未安装时通过 spring.rabbitmq.enabled 关闭），
            // 通知只是业务操作的副作用，不能让它的失败把主流程（如工单升级）打成 500
            log.error("发送工单通知消息失败（MQ 不可用）: ticketId={}, 原因: {}",
                    message.getTicketId(), e.getMessage());
        }
    }
}
