package com.ticketide.enums;

public enum TicketPriority {
    LOW("LOW", "低"),
    MEDIUM("MEDIUM", "中"),
    HIGH("HIGH", "高");

    private final String code;
    private final String desc;

    TicketPriority(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static TicketPriority fromCode(String code) {
        for (TicketPriority priority : values()) {
            if (priority.code.equals(code)) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Invalid ticket priority code: " + code);
    }
}
