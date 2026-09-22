package com.ticketide.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 当前登录用户 ID 注入注解
 * 用在 Controller 方法参数上，自动从 JWT Token 解析 userId 注入
 * 替换 @RequestHeader("X-User-Id") Long userId
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
