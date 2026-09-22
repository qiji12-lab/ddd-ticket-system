package com.ticketide.interceptor;

import com.ticketide.annotation.RateLimit;
import com.ticketide.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.method.HandlerMethod;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitInterceptorTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    /**
     * 限流 key 会优先从 JWT 取用户ID（取不到再回退到 X-User-Id），因此必须注入 JwtUtil，
     * 否则 USER 维度限流的用例会因 jwtUtil 为 null 直接 NPE
     */
    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private RateLimitInterceptor rateLimitInterceptor;

    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        responseWriter = new StringWriter();
        // 让 response.getWriter() 返回我们可控的 StringWriter，便于断言响应内容
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    // ---------------- 无 @RateLimit 注解 ----------------

    @Test
    @DisplayName("方法无@RateLimit注解时直接放行")
    void shouldPassWhenNoRateLimitAnnotation() throws Exception {
        HandlerMethod handler = createHandlerMethod("noLimitMethod");

        boolean result = rateLimitInterceptor.preHandle(request, response, handler);

        assertTrue(result, "无限流注解应放行");
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
    }

    // ---------------- Lua 返回放行 ----------------

    @Test
    @DisplayName("Lua返回1时放行请求")
    void shouldPassWhenLuaReturnsOne() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        HandlerMethod handler = createHandlerMethod("ipLimitedMethod");

        boolean result = rateLimitInterceptor.preHandle(request, response, handler);

        assertTrue(result, "Lua返回1应放行");
    }

    // ---------------- Lua 返回限流 ----------------

    @Test
    @DisplayName("Lua返回0时拦截并返回429")
    void shouldBlockWhenLuaReturnsZero() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(0L);
        // objectMapper 序列化返回固定字符串
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"code\":429}");
        HandlerMethod handler = createHandlerMethod("ipLimitedMethod");

        boolean result = rateLimitInterceptor.preHandle(request, response, handler);

        assertFalse(result, "Lua返回0应被拦截");
        verify(response).setStatus(429);
        verify(response).setContentType("application/json;charset=UTF-8");
    }

    // ---------------- IP 维度限流 ----------------

    @Test
    @DisplayName("IP维度：使用RemoteAddr作为限流key")
    void shouldUseIpAsKeyWhenKeyTypeIsIp() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        HandlerMethod handler = createHandlerMethod("ipLimitedMethod");

        rateLimitInterceptor.preHandle(request, response, handler);

        // 验证传给Lua的第一个参数（key列表）包含IP
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.get(0).contains("192.168.1.100")),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("IP维度：优先读取X-Forwarded-For头")
    void shouldUseXForwardedForWhenPresent() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");
        HandlerMethod handler = createHandlerMethod("ipLimitedMethod");

        rateLimitInterceptor.preHandle(request, response, handler);

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.get(0).contains("10.0.0.1")),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("IP维度：X-Forwarded-For为unknown时回退RemoteAddr")
    void shouldFallbackToRemoteAddrWhenForwardedForIsUnknown() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(request.getHeader("X-Forwarded-For")).thenReturn("unknown");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        HandlerMethod handler = createHandlerMethod("ipLimitedMethod");

        rateLimitInterceptor.preHandle(request, response, handler);

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.get(0).contains("127.0.0.1")),
                anyString(), anyString(), anyString());
    }

    // ---------------- USER 维度限流 ----------------

    @Test
    @DisplayName("USER维度：使用X-User-Id作为限流key")
    void shouldUseUserIdAsKeyWhenKeyTypeIsUser() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(request.getHeader("X-User-Id")).thenReturn("42");
        HandlerMethod handler = createHandlerMethod("userLimitedMethod");

        rateLimitInterceptor.preHandle(request, response, handler);

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.get(0).contains("user:42")),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("USER维度：未提供X-User-Id时回退到IP限流")
    void shouldFallbackToIpWhenUserIdMissing() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(request.getHeader("X-User-Id")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        HandlerMethod handler = createHandlerMethod("userLimitedMethod");

        rateLimitInterceptor.preHandle(request, response, handler);

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.get(0).contains("ip:127.0.0.1")),
                anyString(), anyString(), anyString());
    }

    // ---------------- METHOD 维度限流 ----------------

    @Test
    @DisplayName("METHOD维度：key中包含类名和方法名")
    void shouldUseMethodAsKeyWhenKeyTypeIsMethod() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        HandlerMethod handler = createHandlerMethod("methodLimitedMethod");

        rateLimitInterceptor.preHandle(request, response, handler);

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                argThat(keys -> {
                    String key = keys.get(0);
                    return key.contains("method:") && key.contains("DummyController") && key.contains("methodLimitedMethod");
                }),
                anyString(), anyString(), anyString());
    }

    // ---------------- Lua 参数正确性 ----------------

    @Test
    @DisplayName("传递给Lua的窗口大小和限制次数正确")
    void shouldPassCorrectWindowAndLimitToLua() throws Exception {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        HandlerMethod handler = createHandlerMethod("customLimitMethod");

        rateLimitInterceptor.preHandle(request, response, handler);

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), anyList(),
                anyString(),                        // ARGV[1]: now（时间戳）
                eq("10000"),                         // ARGV[2]: window=10s -> 10000ms
                eq("5"));                            // ARGV[3]: limit=5
    }

    // ---------------- 注解默认值测试 ----------------

    @Test
    @DisplayName("@RateLimit注解默认值校验")
    void rateLimitAnnotationDefaults() throws Exception {
        Method method = DummyController.class.getMethod("defaultLimitMethod");
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);

        assertEquals(20, rateLimit.limit(), "默认limit应为20");
        assertEquals(1, rateLimit.window(), "默认window应为1");
        assertEquals(RateLimit.KeyType.IP, rateLimit.keyType(), "默认keyType应为IP");
        assertEquals("请求过于频繁，请稍后重试", rateLimit.message(), "默认message不匹配");
    }

    // ---------------- 非 HandlerMethod ----------------

    @Test
    @DisplayName("handler非HandlerMethod时直接放行（静态资源等）")
    void shouldPassWhenHandlerIsNotHandlerMethod() throws Exception {
        Object handler = new Object();

        boolean result = rateLimitInterceptor.preHandle(request, response, handler);

        assertTrue(result, "非方法处理器应放行");
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
    }

    // ================= 辅助方法 =================

    /**
     * 创建指向 DummyController 中指定方法的 HandlerMethod
     */
    private HandlerMethod createHandlerMethod(String methodName) throws Exception {
        DummyController controller = new DummyController();
        Method method = DummyController.class.getMethod(methodName);
        return new HandlerMethod(controller, method);
    }

    /**
     * 测试用的虚拟 Controller，包含各种限流配置的方法
     */
    static class DummyController {

        public void noLimitMethod() {}

        @RateLimit
        public void defaultLimitMethod() {}

        @RateLimit(keyType = RateLimit.KeyType.IP)
        public void ipLimitedMethod() {}

        @RateLimit(keyType = RateLimit.KeyType.USER)
        public void userLimitedMethod() {}

        @RateLimit(keyType = RateLimit.KeyType.METHOD)
        public void methodLimitedMethod() {}

        @RateLimit(limit = 5, window = 10)
        public void customLimitMethod() {}
    }
}
