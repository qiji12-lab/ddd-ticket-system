package com.ticketide.interceptor;

import com.ticketide.annotation.RateLimit;
import com.ticketide.dto.response.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collections;
import java.util.List;

/**
 * 限流拦截器，基于 Redis 滑动窗口算法（Lua脚本保证原子性）
 * 在 RoleInterceptor 之前执行，优先对请求做流量控制
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final com.ticketide.util.JwtUtil jwtUtil;

    /**
     * 滑动窗口限流 Lua 脚本
     * KEYS[1]: 限流key
     * ARGV[1]: 当前时间戳（毫秒）
     * ARGV[2]: 窗口大小（毫秒）
     * ARGV[3]: 最大请求次数
     *
     * 逻辑：
     * 1. 移除窗口外的旧请求记录
     * 2. 统计当前窗口内的请求数
     * 3. 若未超限，添加当前请求记录并返回1；否则返回0
     */
    private static final String LUA_SCRIPT =
            "local key = KEYS[1] " +
            "local now = tonumber(ARGV[1]) " +
            "local window = tonumber(ARGV[2]) " +
            "local limit = tonumber(ARGV[3]) " +
            "local windowStart = now - window " +
            "redis.call('ZREMRANGEBYSCORE', key, 0, windowStart) " +
            "local count = redis.call('ZCARD', key) " +
            "if count < limit then " +
            "    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000)) " +
            "    redis.call('EXPIRE', key, window / 1000 + 1) " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";

    private final DefaultRedisScript<Long> rateLimitScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);

        // 未标注限流注解，直接放行
        if (rateLimit == null) {
            return true;
        }

        String limitKey = buildLimitKey(rateLimit, request, handlerMethod);
        long now = System.currentTimeMillis();
        long windowMs = rateLimit.window() * 1000L;

        List<String> keys = Collections.singletonList(limitKey);
        Long result = stringRedisTemplate.execute(
                rateLimitScript,
                keys,
                String.valueOf(now),
                String.valueOf(windowMs),
                String.valueOf(rateLimit.limit())
        );

        // result=1 表示放行，0 表示被限流
        if (result != null && result == 1L) {
            return true;
        }

        log.warn("接口限流触发: key={}, limit={}/{}s, uri={}",
                limitKey, rateLimit.limit(), rateLimit.window(), request.getRequestURI());

        writeErrorResponse(response, 429, rateLimit.message());
        return false;
    }

    /**
     * 根据限流维度构建 Redis key
     */
    private String buildLimitKey(RateLimit rateLimit, HttpServletRequest request, HandlerMethod handlerMethod) {
        String prefix = "rate_limit:";
        String methodKey = handlerMethod.getBeanType().getSimpleName() + ":" + handlerMethod.getMethod().getName();

        return switch (rateLimit.keyType()) {
            case IP -> prefix + "ip:" + getClientIp(request) + ":" + methodKey;
            case USER -> {
                // 优先从 JWT Token 提取 userId
                String authHeader = request.getHeader("Authorization");
                String token = jwtUtil.extractToken(authHeader);
                String userId = null;
                if (token != null) {
                    try {
                        userId = String.valueOf(jwtUtil.getUserIdFromToken(token));
                    } catch (Exception ignored) {
                    }
                }
                // 兼容过渡期：从 X-User-Id 获取
                if (userId == null || userId.isBlank()) {
                    userId = request.getHeader("X-User-Id");
                }
                if (userId == null || userId.isBlank()) {
                    // 未登录用户回退到IP限流
                    yield prefix + "ip:" + getClientIp(request) + ":" + methodKey;
                }
                yield prefix + "user:" + userId + ":" + methodKey;
            }
            case METHOD -> prefix + "method:" + methodKey;
        };
    }

    /**
     * 获取客户端真实IP（兼容反向代理）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            // 多级代理取第一个
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    private void writeErrorResponse(HttpServletResponse response, int code, String message) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code);
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }
}
