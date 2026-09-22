package com.ticketide.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解，基于 Redis 滑动窗口实现
 * 可在 Controller 方法上标注，控制指定时间窗口内的最大请求次数
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 时间窗口内允许的最大请求次数，默认 20 次
     */
    int limit() default 20;

    /**
     * 时间窗口大小（秒），默认 1 秒
     */
    int window() default 1;

    /**
     * 限流维度
     * IP: 按客户端IP限流
     * USER: 按登录用户ID限流（需X-User-Id请求头）
     * METHOD: 按接口方法全局限流
     */
    KeyType keyType() default KeyType.IP;

    /**
     * 被限流时返回的提示信息
     */
    String message() default "请求过于频繁，请稍后重试";

    enum KeyType {
        IP, USER, METHOD
    }
}
