package com.ticketide.controller;

import com.ticketide.dto.request.CommentCreateRequest;
import com.ticketide.dto.response.CommentResponse;
import com.ticketide.dto.response.Result;
import com.ticketide.entity.Comment;
import com.ticketide.service.CommentService;
import com.ticketide.service.TicketService;
import com.ticketide.annotation.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
@Slf4j
public class CommentController {

    private final CommentService commentService;
    private final TicketService ticketService;

    @PostMapping
    public Result<CommentResponse> createComment(
            @RequestParam Long ticketId,
            @Valid @RequestBody CommentCreateRequest request,
            @CurrentUser Long userId) {
        Comment comment = commentService.createComment(ticketId, userId, request);
        return Result.success(CommentResponse.fromEntity(comment));
    }

    @GetMapping
    public Result<List<CommentResponse>> getComments(
            @RequestParam Long ticketId,
            @CurrentUser Long userId) {
        // 通过 TicketService 校验用户是否有权查看该工单
        String role = ticketService.getUserRole(userId);
        ticketService.getTicketDetail(ticketId, userId, role);

        List<Comment> comments = commentService.getCommentsByTicketId(ticketId);
        List<CommentResponse> responses = comments.stream()
                .map(CommentResponse::fromEntity)
                .collect(Collectors.toList());
        return Result.success(responses);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteComment(
            @PathVariable Long id,
            @CurrentUser Long userId) {
        String role = ticketService.getUserRole(userId);
        commentService.deleteComment(id, userId, role);
        return Result.success();
    }
}
