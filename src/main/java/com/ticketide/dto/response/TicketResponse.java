package com.ticketide.dto.response;

import com.ticketide.entity.Ticket;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketResponse {

    private Long id;

    private String title;

    private String description;

    private String priority;

    private String priorityDesc;

    private String category;

    private String categoryDesc;

    private String status;

    private String statusDesc;

    private Long customerId;

    private Long agentId;

    private String customerName;

    private String agentName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static TicketResponse fromEntity(Ticket ticket) {
        TicketResponse response = new TicketResponse();
        response.setId(ticket.getId());
        response.setTitle(ticket.getTitle());
        response.setDescription(ticket.getDescription());
        response.setPriority(ticket.getPriority());
        response.setCategory(ticket.getCategory());
        response.setStatus(ticket.getStatus());
        response.setCustomerId(ticket.getCustomerId());
        response.setAgentId(ticket.getAgentId());
        response.setCustomerName(ticket.getCustomerName());
        response.setAgentName(ticket.getAgentName());
        response.setCreateTime(ticket.getCreateTime());
        response.setUpdateTime(ticket.getUpdateTime());
        return response;
    }
}
