package com.ticketide.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TicketStatusChangeRequest {

    @NotBlank(message = "目标状态不能为空")
    private String targetStatus;
}
