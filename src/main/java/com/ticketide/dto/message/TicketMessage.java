package com.ticketide.dto.message;

import lombok.Data;

@Data
public class TicketMessage {

    private Long ticketId;

    private String title;

    private Long creatorId;

    private String creatorName;

    private String creatorEmail;

    private Long assigneeId;

    private String assigneeName;

    private String assigneeEmail;

    private String action;

    private String status;

    private String description;
}
