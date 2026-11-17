package com.ticketide.consumer;

import com.rabbitmq.client.Channel;
import com.ticketide.dto.message.TicketNotificationMessage;
import com.ticketide.service.EmailService;
import com.ticketide.service.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邮件消费者测试：覆盖正常消费、异常死信、幂等去重。
 * 对应测试用例：TC-302 MQ异步发信、TC-303 死信队列、TC-304 消息幂等性
 */
@ExtendWith(MockitoExtension.class)
class EmailConsumerTest {

    @Mock private EmailService emailService;
    @Mock private RedisService redisService;
    @Mock private Channel channel;

    private EmailConsumer emailConsumer;

    @BeforeEach
    void setUp() {
        emailConsumer = new EmailConsumer(emailService, redisService);
    }

    private TicketNotificationMessage buildMessage(String messageId, Long ticketId, String email) {
        return new TicketNotificationMessage(messageId, ticketId, email, "测试工单", "PROCESSING", "处理中");
    }

    // ==================== TC-302 正常消费 ====================

    @Nested
    @DisplayName("TC-302 MQ异步发信")
    class NormalConsumption {

        @Test
        @DisplayName("正常消费：发送邮件并ACK")
        void shouldSendEmailAndAck() throws IOException {
            TicketNotificationMessage msg = buildMessage("msg-001", 1L, "customer@test.com");
            when(redisService.setIfAbsent(anyString(), anyLong())).thenReturn(true);

            emailConsumer.handleTicketNotification(msg, channel, 100L);

            // 验证邮件被发送
            verify(emailService).sendStatusChangeEmail(msg);
            // 验证消息被 ACK（非 requeue）
            verify(channel).basicAck(eq(100L), eq(false));
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        }
    }

    // ==================== TC-303 死信队列 ====================

    @Nested
    @DisplayName("TC-303 消息可靠性（死信队列）")
    class DeadLetterQueue {

        @Test
        @DisplayName("消费失败：回滚幂等标记并NACK（requeue=false 进入死信队列）")
        void shouldNackAndRollbackIdempotentKeyOnFailure() throws IOException {
            TicketNotificationMessage msg = buildMessage("msg-002", 2L, "agent@test.com");
            when(redisService.setIfAbsent(anyString(), anyLong())).thenReturn(true);
            // 模拟邮件发送失败
            org.mockito.Mockito.doThrow(new RuntimeException("SMTP连接超时"))
                    .when(emailService).sendStatusChangeEmail(any());

            emailConsumer.handleTicketNotification(msg, channel, 200L);

            // 验证幂等标记被回滚（允许重试时重新处理）
            verify(redisService).delete(anyString());
            // 验证 NACK + requeue=false（进入死信队列）
            verify(channel).basicNack(eq(200L), eq(false), eq(false));
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
        }
    }

    // ==================== TC-304 消息幂等性 ====================

    @Nested
    @DisplayName("TC-304 消息幂等性")
    class Idempotency {

        @Test
        @DisplayName("重复消息：跳过发送，直接ACK")
        void shouldSkipDuplicateMessageAndAck() throws IOException {
            TicketNotificationMessage msg = buildMessage("msg-003", 3L, "admin@test.com");
            // Redis 中已存在幂等标记 → 重复消息
            when(redisService.setIfAbsent(anyString(), anyLong())).thenReturn(false);

            emailConsumer.handleTicketNotification(msg, channel, 300L);

            // 验证不重复发送邮件
            verify(emailService, never()).sendStatusChangeEmail(any());
            // 验证仍然 ACK（消息已被"处理"过，只是跳过了重复操作）
            verify(channel).basicAck(eq(300L), eq(false));
        }

        @Test
        @DisplayName("无messageId时用 ticketId:status 作为幂等键")
        void shouldUseTicketIdAndStatusAsFallbackIdempotentKey() throws IOException {
            TicketNotificationMessage msg = buildMessage(null, 5L, "user@test.com");
            when(redisService.setIfAbsent(anyString(), anyLong())).thenReturn(true);

            emailConsumer.handleTicketNotification(msg, channel, 400L);

            // 验证幂等键包含 ticketId:status 格式
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisService).setIfAbsent(keyCaptor.capture(), anyLong());
            assertTrue(keyCaptor.getValue().contains("5:PROCESSING"),
                    "幂等键应包含 ticketId:status，实际: " + keyCaptor.getValue());
            verify(emailService).sendStatusChangeEmail(msg);
        }
    }
}
