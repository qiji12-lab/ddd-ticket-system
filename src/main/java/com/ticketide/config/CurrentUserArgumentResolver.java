package com.ticketide.config;

import com.ticketide.annotation.CurrentUser;
import com.ticketide.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 @CurrentUser 注解，从 Authorization: Bearer <token> 提取 userId
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final JwtUtil jwtUtil;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType().equals(Long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            return null;
        }

        String authHeader = request.getHeader("Authorization");
        String token = jwtUtil.extractToken(authHeader);

        if (token == null) {
            // 兼容过渡期：尝试从 X-User-Id 获取
            String userIdHeader = request.getHeader("X-User-Id");
            if (userIdHeader != null && !userIdHeader.isEmpty()) {
                try {
                    return Long.parseLong(userIdHeader);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);
            return claims.get("userId", Long.class);
        } catch (Exception e) {
            log.warn("解析 JWT 失败: {}", e.getMessage());
            return null;
        }
    }
}
