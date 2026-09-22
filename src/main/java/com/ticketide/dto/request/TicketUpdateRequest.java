package com.ticketide.dto.request;

import lombok.Data;

@Data
public class TicketUpdateRequest {

    private String title;

    private String description;

    private String priority;
}
