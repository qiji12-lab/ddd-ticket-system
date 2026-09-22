package com.ticketide.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TicketEscalateRequest {

    /**
     * 升级原因，会写入操作日志的备注字段并透出给主管
     */
    @Size(max = 400, message = "升级说明长度不能超过400")
    private String remark;
}
