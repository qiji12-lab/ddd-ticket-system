package com.ticketide.dto.response;

import com.ticketide.entity.Comment;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CommentResponse {

    private Long id;
    private Long ticketId;
    private Long userId;
    private String username;
    private String userRole;
    private String content;
    private LocalDateTime createTime;

    public static CommentResponse fromEntity(Comment comment) {
        CommentResponse response = new CommentResponse();
        response.setId(comment.getId());
        response.setTicketId(comment.getTicketId());
        response.setUserId(comment.getUserId());
        response.setUsername(comment.getUsername());
        response.setUserRole(comment.getUserRole());
        response.setContent(comment.getContent());
        response.setCreateTime(comment.getCreateTime());
        return response;
    }
}
