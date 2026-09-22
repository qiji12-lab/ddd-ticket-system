package com.ticketide.enums;

import java.util.Arrays;
import java.util.List;

public enum TicketStatus {
    PENDING("PENDING", "待接单"),
    ASSIGNED("ASSIGNED", "已分配"),
    PROCESSING("PROCESSING", "处理中"),
    RESOLVED("RESOLVED", "已解决"),
    PENDING_CONFIRM("PENDING_CONFIRM", "待确认"),
    REOPENED("REOPENED", "已重开"),
    CLOSED("CLOSED", "已关闭"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String desc;

    TicketStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public boolean canTransitionTo(TicketStatus targetStatus) {
        return getAllowedTransitions().contains(targetStatus);
    }

    /**
     * 合法流转表（状态机核心约束）：
     * PENDING(待接单)      -> ASSIGNED(管理员派单) / PROCESSING(抢单) / CANCELLED(客户取消)
     * ASSIGNED(已分配)     -> PROCESSING(经办人开始处理)
     * PROCESSING(处理中)   -> RESOLVED(经办人标记已解决) / CANCELLED(客户取消)
     * RESOLVED(已解决)     -> PENDING_CONFIRM(自动流转，等待客户确认)
     *                        兼容历史数据，亦可直接 CLOSED / REOPENED
     * PENDING_CONFIRM(待确认) -> CLOSED(客户确认关闭) / REOPENED(客户不满意重开)
     * REOPENED(已重开)     -> PROCESSING(重新处理)
     * CLOSED(已关闭)       -> REOPENED(管理员重新打开)
     * CANCELLED(已取消)    -> 终态，不可再流转（如需恢复只能由管理员新建工单）
     * <p>
     * 说明：状态允许的"下一步"集中维护在此处，新增状态只需改这一张表，
     * Service 层无需任何 if-else 判断，避免状态判断逻辑散落各处。
     */
    private List<TicketStatus> getAllowedTransitions() {
        return switch (this) {
            case PENDING -> Arrays.asList(ASSIGNED, PROCESSING, CANCELLED);
            case ASSIGNED -> List.of(PROCESSING);
            case PROCESSING -> Arrays.asList(RESOLVED, CANCELLED);
            case RESOLVED -> Arrays.asList(PENDING_CONFIRM, CLOSED, REOPENED);
            case PENDING_CONFIRM -> Arrays.asList(CLOSED, REOPENED);
            case REOPENED -> List.of(PROCESSING);
            case CLOSED -> List.of(REOPENED);
            case CANCELLED -> List.of();
        };
    }

    /**
     * 是否为终态：终态工单不再接受任何流转
     */
    public boolean isTerminal() {
        return getAllowedTransitions().isEmpty();
    }

    public static TicketStatus fromCode(String code) {
        for (TicketStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid ticket status code: " + code);
    }
}
