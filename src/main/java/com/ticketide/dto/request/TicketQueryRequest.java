package com.ticketide.dto.request;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 工单多条件查询参数（GET 查询串绑定）。
 * 分页参数由 Page 单独承载，这里只放筛选条件，避免 Service 方法签名无限膨胀。
 */
@Data
public class TicketQueryRequest {

    /**
     * 状态：PENDING/ASSIGNED/PROCESSING/RESOLVED/PENDING_CONFIRM/REOPENED/CLOSED/CANCELLED
     */
    private String status;

    /**
     * 优先级：HIGH/MEDIUM/LOW
     */
    private String priority;

    /**
     * 问题分类：TECHNICAL/ACCOUNT/COMPLAINT
     */
    private String category;

    /**
     * 关键词：同时匹配标题与描述（LIKE 通配符会被转义）
     */
    private String keyword;

    /**
     * 提交时间范围（起），按日期筛选，含当天 00:00:00
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startTime;

    /**
     * 提交时间范围（止），按日期筛选，含当天 23:59:59
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endTime;

    /**
     * 数据范围：mine-仅我负责的；留空表示角色默认可见范围
     */
    private String scope;
}
