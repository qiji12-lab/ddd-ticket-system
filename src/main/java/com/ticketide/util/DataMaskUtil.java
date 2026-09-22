package com.ticketide.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据脱敏工具。
 * 应用场景：工单备注、沟通内容由用户自由填写，可能夹带邮箱、手机号，
 * 这些内容会随工单详情（时间线）返回给其他角色（客服/管理员）查看，
 * 因此在出参处统一打码，避免把用户的联系方式扩散给无关人员。
 */
public final class DataMaskUtil {

    /**
     * 邮箱：保留首字符 + 域名，如 zhangsan@qq.com -> z***@qq.com
     */
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+)");

    /**
     * 手机号：保留前 3 位与后 4 位，如 13812345678 -> 138****5678
     */
    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");

    private DataMaskUtil() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = PHONE.matcher(text).replaceAll("$1****$2");
        Matcher matcher = EMAIL.matcher(masked);
        return matcher.replaceAll("$1***$2");
    }
}
