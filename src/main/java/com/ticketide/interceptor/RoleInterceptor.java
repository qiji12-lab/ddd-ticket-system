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

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoleInterceptor implements HandlerInterceptor {

    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;

    /**
     * 免登录白名单：登录、注册本身发生在拿到 Token 之前
     */
    private static final Set<String> PUBLIC_URIS = Set.of("/api/users/login", "/api/users/register");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // 【第一道关卡：登录认证】对 /api/** 下所有业务接口统一校验 JWT，
        // 而不是只校验带 @RequiresRole 的接口——否则未登录请求会带着 null 的 operatorId
        // 穿透到 Service 层，数据权限判断（role 为 null）被整体跳过，等于裸奔。
        if (!PUBLIC_URIS.contains(request.getRequestURI())) {
            Long userId = getCurrentUserId(request);
            if (userId == null) {
                writeErrorResponse(response, 401, "未登录或Token无效");
                return false;
            }
            if (userService.getUserById(userId) == null) {
                writeErrorResponse(response, 401, "用户不存在");
                return false;
            }
        }

        // 【第二道关卡：角色鉴权】仅针对显式声明 @RequiresRole 的接口
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequiresRole requiresRole = handlerMethod.getMethodAnnotation(RequiresRole.class);

        if (requiresRole == null) {
            return true;
        }

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
        // 只信任服务端签发的 JWT：用户身份必须从 Authorization: Bearer <token> 解析。
        // 不再兼容 X-User-Id 请求头——客户端可随意伪造该头冒充任意用户（含管理员），
        // 等于给所有数据权限校验开了后门。
        String authHeader = request.getHeader("Authorization");
        String token = jwtUtil.extractToken(authHeader);
        if (token == null) {
            return null;
        }
        try {
            Claims claims = jwtUtil.parseToken(token);
            return claims.get("userId", Long.class);
        } catch (Exception e) {
            log.warn("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private void writeErrorResponse(HttpServletResponse response, int code, String message) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code);
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }
}
