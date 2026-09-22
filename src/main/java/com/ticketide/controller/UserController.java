package com.ticketide.controller;

import com.ticketide.dto.request.UserLoginRequest;
import com.ticketide.dto.request.UserRegisterRequest;
import com.ticketide.dto.response.LoginResponse;
import com.ticketide.dto.response.Result;
import com.ticketide.dto.response.UserResponse;
import com.ticketide.entity.User;
import com.ticketide.enums.UserRole;
import com.ticketide.annotation.RateLimit;
import com.ticketide.annotation.RequiresRole;
import com.ticketide.service.UserService;
import com.ticketide.annotation.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    @PostMapping("/login")
    @RateLimit(limit = 10, window = 60, keyType = RateLimit.KeyType.IP, message = "登录尝试过于频繁，请60秒后再试")
    public Result<LoginResponse> login(@Valid @RequestBody UserLoginRequest request) {
        LoginResponse response = userService.login(request);
        return Result.success(response);
    }

    @PostMapping("/register")
    @RateLimit(limit = 3, window = 60, keyType = RateLimit.KeyType.IP, message = "注册过于频繁，请60秒后再试")
    public Result<UserResponse> register(@Valid @RequestBody UserRegisterRequest request) {
        UserResponse response = userService.register(request);
        return Result.success(response);
    }

    @GetMapping("/me")
    public Result<UserResponse> getCurrentUser(@CurrentUser Long userId) {
        User user = userService.getUserById(userId);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(UserResponse.fromEntity(user));
    }

    @GetMapping
    @RequiresRole({UserRole.ADMIN})
    public Result<List<UserResponse>> getAllUsers() {
        List<User> users = userService.getAllUsers();
        List<UserResponse> responses = users.stream()
                .map(UserResponse::fromEntity)
                .collect(Collectors.toList());
        return Result.success(responses);
    }

    @GetMapping("/role/{role}")
    @RequiresRole({UserRole.ADMIN})
    public Result<List<UserResponse>> getUsersByRole(@PathVariable String role) {
        List<User> users = userService.getUsersByRole(role);
        List<UserResponse> responses = users.stream()
                .map(UserResponse::fromEntity)
                .collect(Collectors.toList());
        return Result.success(responses);
    }

    @GetMapping("/{id}")
    public Result<UserResponse> getUserById(@PathVariable Long id) {
        User user = userService.getUserById(id);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(UserResponse.fromEntity(user));
    }
}
