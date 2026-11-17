package com.ticketide.exception;

import com.ticketide.dto.response.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器测试：覆盖参数校验、业务异常、系统异常的统一响应。
 * 对应测试用例：TC-401 参数校验异常、TC-402 业务异常、TC-403 系统异常
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    // ==================== TC-401 参数校验异常 ====================

    @Nested
    @DisplayName("TC-401 参数校验异常")
    class ValidationException {

        @Test
        @DisplayName("标题为空时返回400和字段错误信息")
        void shouldReturn400WithFieldErrors() {
            // 模拟 @Valid 校验失败
            BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "ticketCreateRequest");
            bindingResult.addError(new FieldError("ticketCreateRequest", "title", "标题不能为空"));
            bindingResult.addError(new FieldError("ticketCreateRequest", "priority", "优先级不能为空"));
            MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

            Result<Map<String, String>> result = handler.handleValidationExceptions(ex);

            assertEquals(400, result.getCode());
            assertTrue(result.getMessage().contains("参数校验失败"));
            // 错误详情拼在 message 中
            assertTrue(result.getMessage().contains("标题不能为空"));
            assertTrue(result.getMessage().contains("优先级不能为空"));
        }
    }

    // ==================== TC-402 业务异常 ====================

    @Nested
    @DisplayName("TC-402 业务异常")
    class BusinessExceptionHandler {

        @Test
        @DisplayName("BusinessException返回400和业务错误消息")
        void shouldReturn400WithBusinessMessage() {
            BusinessException ex = new BusinessException("工单不存在");

            ResponseEntity<Result<Void>> response = handler.handleBusinessException(ex);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals(400, response.getBody().getCode());
            assertEquals("工单不存在", response.getBody().getMessage());
        }

        @Test
        @DisplayName("带403状态码的BusinessException返回403")
        void shouldReturn403WhenCodeIs403() {
            BusinessException ex = new BusinessException(403, "无权操作此工单");

            ResponseEntity<Result<Void>> response = handler.handleBusinessException(ex);

            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertEquals(403, response.getBody().getCode());
            assertEquals("无权操作此工单", response.getBody().getMessage());
        }
    }

    // ==================== TC-403 系统异常 ====================

    @Nested
    @DisplayName("TC-403 系统异常")
    class SystemExceptionHandler {

        @Test
        @DisplayName("RuntimeException返回500和友好提示，不暴露堆栈")
        void shouldReturn500WithFriendlyMessage() {
            RuntimeException ex = new RuntimeException("Communications link failure due to underlying exception");

            Result<Void> result = handler.handleRuntimeException(ex);

            assertEquals(500, result.getCode());
            assertEquals("系统繁忙，请稍后重试", result.getMessage());
            // 不应暴露数据库错误细节
            assertTrue(!result.getMessage().contains("Communications link failure"));
        }

        @Test
        @DisplayName("Exception返回500和友好提示")
        void shouldReturn500ForGenericException() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRequestURI()).thenReturn("/api/tickets/1");
            Exception ex = new Exception("java.net.ConnectException: Connection refused");

            Result<Void> result = handler.handleException(ex, request);

            assertEquals(500, result.getCode());
            assertTrue(result.getMessage().contains("系统异常"));
        }
    }
}
