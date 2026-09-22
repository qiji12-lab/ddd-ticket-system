package com.ticketide.service;

import com.ticketide.dto.message.TicketNotificationMessage;

public interface RabbitMQService {

    void sendNotification(TicketNotificationMessage message);
}
