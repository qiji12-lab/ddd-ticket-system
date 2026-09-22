package com.ticketide.interceptor;

import com.ticketide.annotation.RequiresRole;
import com.ticketide.dto.response.Result;
import com.ticketide.enums.UserRole;
import com.ticketide.service.UserService;
import com.ticketide.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoleInterceptor implements HandlerInterceptor {

    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequiresRole requiresRole = handlerMethod.getMethodAnnotation(RequiresRole.class);

        if (requiresRole == null) {
            return true;
        }

        // 优先从 JWT Token 获取用户身份
        Long userId = getCurrentUserId(request);
        if (userId == null) {
            writeErrorResponse(response, 401, "未登录或Token无效");
            return false;
        }

        var user = userService.getUserById(userId);
        if (user == null) {
            writeErrorResponse(response, 401, "用户不存在");
            return false;
        }

        UserRole userRole = UserRole.fromCode(user.getRole());
        UserRole[] allowedRoles = requiresRole.value();

        for (UserRole role : allowedRoles) {
            if (role.equals(userRole)) {
                return true;
            }
        }

        writeErrorResponse(response, 403, "没有权限访问此接口");
        return false;
    }

    private Long getCurrentUserId(HttpServletRequest request) {
        // 优先从 Authorization: Bearer <token> 提取
        String authHeader = request.getHeader("Authorization");
        String token = jwtUtil.extractToken(authHeader);
        if (token != null) {
            try {
                Claims claims = jwtUtil.parseToken(token);
                return claims.get("userId", Long.class);
            } catch (Exception e) {
                log.warn("JWT 解析失败: {}", e.getMessage());
                return null;
            }
        }

        // 兼容过渡期：从 X-User-Id 获取
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null && !userIdHeader.isEmpty()) {
            try {
                return Long.parseLong(userIdHeader);
            } catch (NumberFormatException e) {
                log.warn("Invalid user id in header: {}", userIdHeader);
            }
        }
        return null;
    }

    private void writeErrorResponse(HttpServletResponse response, int code, String message) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code);
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }
}
