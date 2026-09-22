package com.ticketide.service;

import com.ticketide.dto.request.CommentCreateRequest;
import com.ticketide.entity.Comment;

import java.util.List;

public interface CommentService {

    Comment createComment(Long ticketId, Long userId, CommentCreateRequest request);

    List<Comment> getCommentsByTicketId(Long ticketId);

    void deleteComment(Long id, Long userId, String role);
}
