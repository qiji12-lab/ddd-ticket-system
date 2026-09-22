package com.ticketide.service.impl;

import com.ticketide.dto.request.CommentCreateRequest;
import com.ticketide.entity.Comment;
import com.ticketide.entity.Ticket;
import com.ticketide.entity.User;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.CommentMapper;
import com.ticketide.mapper.TicketMapper;
import com.ticketide.service.CommentService;
import com.ticketide.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private final CommentMapper commentMapper;
    private final TicketMapper ticketMapper;
    private final UserService userService;

    @Override
    @Transactional
    public Comment createComment(Long ticketId, Long userId, CommentCreateRequest request) {
        // 校验工单存在
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        // 数据权限：客户只能评论自己的工单；经办人只能评论自己负责的工单；管理员可评论所有
        User user = userService.getUserById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        String role = user.getRole();
        if ("CUSTOMER".equals(role) && !ticket.getCustomerId().equals(userId)) {
            throw new BusinessException("只能评论自己创建的工单");
        }
        if ("AGENT".equals(role) && !userId.equals(ticket.getAgentId())) {
            throw new BusinessException("只能评论自己负责的工单");
        }

        Comment comment = new Comment();
        comment.setTicketId(ticketId);
        comment.setUserId(userId);
        comment.setContent(request.getContent());
        commentMapper.insert(comment);

        // 填充用户名和角色（用于返回展示）
        comment.setUsername(user.getUsername());
        comment.setUserRole(role);

        return comment;
    }

    @Override
    public List<Comment> getCommentsByTicketId(Long ticketId) {
        List<Comment> comments = commentMapper.selectByTicketId(ticketId);

        // 批量填充用户名和角色
        for (Comment comment : comments) {
            User user = userService.getUserById(comment.getUserId());
            if (user != null) {
                comment.setUsername(user.getUsername());
                comment.setUserRole(user.getRole());
            }
        }

        return comments;
    }

    @Override
    @Transactional
    public void deleteComment(Long id, Long userId, String role) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }

        // 只有评论人本人或管理员可删除
        if (!"ADMIN".equals(role) && !comment.getUserId().equals(userId)) {
            throw new BusinessException("只能删除自己发表的评论");
        }

        commentMapper.deleteById(id);
    }
}
