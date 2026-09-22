package com.ticketide.dto.response;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private Long userId;
    private String username;
    private String role;
    private String roleDesc;
    private String email;
    private String token;  // 简单token，实际项目中应使用JWT
}
