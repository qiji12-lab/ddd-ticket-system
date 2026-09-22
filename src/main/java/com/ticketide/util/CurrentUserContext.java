package com.ticketide.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前登录用户上下文。
 * <p>
 * 从当前请求的 {@code Authorization: Bearer <token>}（兼容过渡期的 {@code X-User-Id}）解析 userId，
 * 供 AOP 切面等"非 Controller"场景获取操作人，避免为了记日志而在每个业务方法上都挂一个 operatorId 参数。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CurrentUserContext {

    private final JwtUtil jwtUtil;

    /**
     * @return 当前登录用户ID；无请求上下文（如定时任务线程）或未登录时返回 null
     */
    public Long getCurrentUserId() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return resolve(attributes.getRequest());
    }

    public Long resolve(HttpServletRequest request) {
        String token = jwtUtil.extractToken(request.getHeader("Authorization"));
        if (token != null) {
            try {
                return jwtUtil.parseToken(token).get("userId", Long.class);
            } catch (Exception e) {
                log.warn("解析 JWT 失败: {}", e.getMessage());
                return null;
            }
        }

        // 兼容过渡期：从 X-User-Id 获取
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null && !userIdHeader.isEmpty()) {
            try {
                return Long.parseLong(userIdHeader);
            } catch (NumberFormatException e) {
                log.warn("X-User-Id 格式错误: {}", userIdHeader);
            }
        }
        return null;
    }
}
