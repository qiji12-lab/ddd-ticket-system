package com.ticketide.enums;

public enum UserRole {
    ADMIN("ADMIN", "管理员"),
    AGENT("AGENT", "代理"),
    CUSTOMER("CUSTOMER", "客户");

    private final String code;
    private final String desc;

    UserRole(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static UserRole fromCode(String code) {
        for (UserRole role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Invalid user role code: " + code);
    }
}
