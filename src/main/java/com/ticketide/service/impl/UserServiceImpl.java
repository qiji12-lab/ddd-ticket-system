package com.ticketide.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketide.dto.request.UserLoginRequest;
import com.ticketide.dto.request.UserRegisterRequest;
import com.ticketide.dto.response.LoginResponse;
import com.ticketide.dto.response.UserResponse;
import com.ticketide.entity.User;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.UserMapper;
import com.ticketide.service.UserService;
import com.ticketide.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    @Override
    public User getUserById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public User getUserByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return userMapper.selectOne(wrapper);
    }

    @Override
    public User getUserByEmail(String email) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, email);
        return userMapper.selectOne(wrapper);
    }

    @Override
    public User createUser(User user) {
        userMapper.insert(user);
        return user;
    }

    @Override
    public User updateUser(User user) {
        userMapper.updateById(user);
        return user;
    }

    @Override
    public void deleteUser(Long id) {
        userMapper.deleteById(id);
    }

    @Override
    public LoginResponse login(UserLoginRequest request) {
        User user = getUserByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        // 使用BCrypt校验密码
        if (!BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        String roleDesc = getRoleDesc(user.getRole());
        // 生成 JWT Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        log.info("用户[{}]登录成功，角色: {}", user.getUsername(), roleDesc);

        return new LoginResponse(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                roleDesc,
                user.getEmail(),
                token
        );
    }

    @Override
    public UserResponse register(UserRegisterRequest request) {
        // 检查用户名是否已存在
        if (getUserByUsername(request.getUsername()) != null) {
            throw new BusinessException("用户名已存在");
        }

        // 检查邮箱是否已存在
        if (getUserByEmail(request.getEmail()) != null) {
            throw new BusinessException("邮箱已被注册");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        // 使用BCrypt加密密码
        user.setPassword(BCrypt.hashpw(request.getPassword(), BCrypt.gensalt()));
        user.setEmail(request.getEmail());
        user.setRole(request.getRole() != null ? request.getRole() : "CUSTOMER");

        userMapper.insert(user);
        log.info("新用户注册成功: {}", user.getUsername());

        return UserResponse.fromEntity(user);
    }

    @Override
    public List<User> getAllUsers() {
        return userMapper.selectList(null);
    }

    @Override
    public List<User> getUsersByRole(String role) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getRole, role);
        return userMapper.selectList(wrapper);
    }

    private String getRoleDesc(String role) {
        return switch (role) {
            case "ADMIN" -> "管理员";
            case "AGENT" -> "经办人";
            case "CUSTOMER" -> "客户";
            default -> role;
        };
    }
}
