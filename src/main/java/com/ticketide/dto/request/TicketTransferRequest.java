package com.ticketide.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TicketTransferRequest {

    @NotNull(message = "被转交的客服不能为空")
    private Long targetAgentId;

    /**
     * 转交原因/说明，会写入操作日志的备注字段，便于事后追溯"为什么转交"
     */
    @Size(max = 400, message = "转交说明长度不能超过400")
    private String remark;
}
