package com.ticketide.dto.response;

import com.ticketide.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserResponse {

    private Long id;
    private String username;
    private String email;
    private String role;
    private String roleDesc;
    private LocalDateTime createTime;

    public static UserResponse fromEntity(User user) {
        if (user == null) {
            return null;
        }
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setCreateTime(user.getCreateTime());
        
        // 角色描述
        switch (user.getRole()) {
            case "ADMIN" -> response.setRoleDesc("管理员");
            case "AGENT" -> response.setRoleDesc("经办人");
            case "CUSTOMER" -> response.setRoleDesc("客户");
            default -> response.setRoleDesc(user.getRole());
        }
        
        return response;
    }
}
