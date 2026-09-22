package com.ticketide.enums;

/**
 * 工单问题分类枚举
 */
public enum TicketCategory {

    TECHNICAL("TECHNICAL", "技术故障"),
    ACCOUNT("ACCOUNT", "账号问题"),
    COMPLAINT("COMPLAINT", "投诉建议");

    private final String code;
    private final String desc;

    TicketCategory(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static TicketCategory fromCode(String code) {
        for (TicketCategory category : values()) {
            if (category.code.equals(code)) {
                return category;
            }
        }
        throw new IllegalArgumentException("无效的工单分类: " + code);
    }

    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        for (TicketCategory category : values()) {
            if (category.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
