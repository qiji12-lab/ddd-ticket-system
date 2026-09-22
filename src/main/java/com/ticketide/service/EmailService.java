package com.ticketide.service;

import com.ticketide.dto.message.TicketMessage;
import com.ticketide.dto.message.TicketNotificationMessage;

public interface EmailService {

    void sendTicketCreatedEmail(TicketMessage message);

    void sendTicketAssignedEmail(TicketMessage message);

    void sendTicketCompletedEmail(TicketMessage message);

    void sendTicketUpdatedEmail(TicketMessage message);

    /**
     * 发送工单状态变更通知邮件（由RabbitMQ消费者调用）
     */
    void sendStatusChangeEmail(TicketNotificationMessage message);
}
