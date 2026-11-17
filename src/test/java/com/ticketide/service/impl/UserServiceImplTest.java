package com.ticketide.service.impl;

import com.ticketide.dto.request.UserLoginRequest;
import com.ticketide.dto.request.UserRegisterRequest;
import com.ticketide.dto.response.LoginResponse;
import com.ticketide.dto.response.UserResponse;
import com.ticketide.entity.User;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.UserMapper;
import com.ticketide.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户服务单元测试：覆盖注册（BCrypt加密存储）与登录（JWT Token签发）。
 * 对应测试用例：TC-001 用户注册、TC-002 用户登录
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtUtil jwtUtil;

    private UserServiceImpl userService;

    /** 动态生成 BCrypt 哈希，保证与明文 "123456" 匹配 */
    private static String hashPassword(String plain) {
        return BCrypt.hashpw(plain, BCrypt.gensalt());
    }

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, jwtUtil);
    }

    private User buildUser(Long id, String username, String role, String password) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@ticketide.com");
        user.setRole(role);
        user.setPassword(password);
        return user;
    }

    // ==================== TC-001 注册 ====================

    @Nested
    @DisplayName("TC-001 用户注册")
    class Register {

        @Test
        @DisplayName("注册成功：密码以BCrypt哈希存储，非明文")
        void shouldRegisterAndHashPassword() {
            // getUserByUsername 和 getUserByEmail 都返回 null（不存在）
            when(userMapper.selectOne(any())).thenReturn(null);

            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername("newuser01");
            request.setPassword("test123456");
            request.setEmail("newuser01@test.com");
            request.setRole("CUSTOMER");

            UserResponse response = userService.register(request);

            assertNotNull(response);
            assertEquals("newuser01", response.getUsername());
            assertEquals("CUSTOMER", response.getRole());
            assertEquals("客户", response.getRoleDesc());

            // 验证传入 mapper 的 User 对象密码是 BCrypt 哈希而非明文
            org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(captor.capture());
            User savedUser = captor.getValue();
            assertTrue(savedUser.getPassword().startsWith("$2a$"), "密码应为BCrypt哈希");
            assertTrue(BCrypt.checkpw("test123456", savedUser.getPassword()), "哈希应能校验明文密码");
        }

        @Test
        @DisplayName("重复用户名被拦截")
        void shouldRejectDuplicateUsername() {
            User existing = buildUser(1L, "admin", "ADMIN", hashPassword("123456"));
            when(userMapper.selectOne(any())).thenReturn(existing);

            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername("admin");
            request.setPassword("123456");
            request.setEmail("new@test.com");
            request.setRole("CUSTOMER");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.register(request));

            assertTrue(ex.getMessage().contains("用户名已存在"));
            verify(userMapper, never()).insert(any());
        }

        @Test
        @DisplayName("重复邮箱被拦截")
        void shouldRejectDuplicateEmail() {
            // 第一次 selectOne（查用户名）返回 null，第二次（查邮箱）返回已存在用户
            User existingByEmail = buildUser(2L, "other", "CUSTOMER", hashPassword("123456"));
            when(userMapper.selectOne(any())).thenReturn(null, existingByEmail);

            UserRegisterRequest request = new UserRegisterRequest();
            request.setUsername("uniqueuser");
            request.setPassword("123456");
            request.setEmail("other@ticketide.com");
            request.setRole("CUSTOMER");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.register(request));

            assertTrue(ex.getMessage().contains("邮箱已被注册"));
            verify(userMapper, never()).insert(any());
        }
    }

    // ==================== TC-002 登录 ====================

    @Nested
    @DisplayName("TC-002 用户登录")
    class Login {

        @Test
        @DisplayName("登录成功：返回JWT Token和用户信息")
        void shouldLoginSuccessfully() {
            User user = buildUser(1L, "admin", "ADMIN", hashPassword("123456"));
            when(userMapper.selectOne(any())).thenReturn(user);
            when(jwtUtil.generateToken(eq(1L), eq("admin"), eq("ADMIN")))
                    .thenReturn("mock.jwt.token");

            UserLoginRequest request = new UserLoginRequest();
            request.setUsername("admin");
            request.setPassword("123456");

            LoginResponse response = userService.login(request);

            assertNotNull(response);
            assertEquals(1L, response.getUserId());
            assertEquals("admin", response.getUsername());
            assertEquals("ADMIN", response.getRole());
            assertEquals("管理员", response.getRoleDesc());
            assertEquals("mock.jwt.token", response.getToken());
        }

        @Test
        @DisplayName("用户不存在时拒绝登录")
        void shouldRejectNonExistentUser() {
            when(userMapper.selectOne(any())).thenReturn(null);

            UserLoginRequest request = new UserLoginRequest();
            request.setUsername("nobody");
            request.setPassword("123456");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.login(request));

            assertTrue(ex.getMessage().contains("用户名或密码错误"));
            verify(jwtUtil, never()).generateToken(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("密码错误时拒绝登录")
        void shouldRejectWrongPassword() {
            User user = buildUser(1L, "admin", "ADMIN", hashPassword("123456"));
            when(userMapper.selectOne(any())).thenReturn(user);

            UserLoginRequest request = new UserLoginRequest();
            request.setUsername("admin");
            request.setPassword("wrong_password");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.login(request));

            assertTrue(ex.getMessage().contains("用户名或密码错误"));
            verify(jwtUtil, never()).generateToken(any(), anyString(), anyString());
        }
    }
}
