package com.ticketide.service;

import com.ticketide.dto.request.UserLoginRequest;
import com.ticketide.dto.request.UserRegisterRequest;
import com.ticketide.dto.response.LoginResponse;
import com.ticketide.dto.response.UserResponse;
import com.ticketide.entity.User;

import java.util.List;

public interface UserService {

    User getUserById(Long id);

    User getUserByUsername(String username);

    User getUserByEmail(String email);

    User createUser(User user);

    User updateUser(User user);

    void deleteUser(Long id);

    LoginResponse login(UserLoginRequest request);

    UserResponse register(UserRegisterRequest request);

    List<User> getAllUsers();

    List<User> getUsersByRole(String role);
}
