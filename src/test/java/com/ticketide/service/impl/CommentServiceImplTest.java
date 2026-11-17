package com.ticketide.service.impl;

import com.ticketide.dto.request.CommentCreateRequest;
import com.ticketide.entity.Comment;
import com.ticketide.entity.Ticket;
import com.ticketide.entity.User;
import com.ticketide.enums.TicketStatus;
import com.ticketide.enums.UserRole;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.CommentMapper;
import com.ticketide.mapper.TicketMapper;
import com.ticketide.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论服务测试：覆盖数据权限校验（客户只能评论自己的工单，客服只能评论负责的工单）。
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock private CommentMapper commentMapper;
    @Mock private TicketMapper ticketMapper;
    @Mock private UserService userService;

    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        commentService = new CommentServiceImpl(commentMapper, ticketMapper, userService);
    }

    private Ticket buildTicket(Long id, Long customerId, Long agentId) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setStatus(TicketStatus.PROCESSING.getCode());
        ticket.setCustomerId(customerId);
        ticket.setAgentId(agentId);
        return ticket;
    }

    private User buildUser(Long id, String username, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role.getCode());
        return user;
    }

    // ==================== 创建评论 ====================

    @Test
    @DisplayName("客户评论自己的工单：成功")
    void shouldAllowCustomerCommentOwnTicket() {
        when(ticketMapper.selectById(1L)).thenReturn(buildTicket(1L, 4L, 2L));
        when(userService.getUserById(4L)).thenReturn(buildUser(4L, "customer01", UserRole.CUSTOMER));
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            Comment c = invocation.getArgument(0);
            c.setId(1L);
            return 1;
        });

        CommentCreateRequest request = new CommentCreateRequest();
        request.setContent("请问什么时候能处理好？");

        Comment result = commentService.createComment(1L, 4L, request);

        assertEquals(1L, result.getId());
        assertEquals(1L, result.getTicketId());
        assertEquals(4L, result.getUserId());
        assertEquals("customer01", result.getUsername());
        assertEquals("CUSTOMER", result.getUserRole());
    }

    @Test
    @DisplayName("客户不能评论他人的工单")
    void shouldRejectCustomerCommentOthersTicket() {
        when(ticketMapper.selectById(2L)).thenReturn(buildTicket(2L, 4L, 2L));
        when(userService.getUserById(5L)).thenReturn(buildUser(5L, "customer02", UserRole.CUSTOMER));

        CommentCreateRequest request = new CommentCreateRequest();
        request.setContent("测试评论");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.createComment(2L, 5L, request));

        assertTrue(ex.getMessage().contains("只能评论自己创建的工单"));
        verify(commentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("客服只能评论自己负责的工单")
    void shouldRejectAgentCommentNonAssignedTicket() {
        when(ticketMapper.selectById(3L)).thenReturn(buildTicket(3L, 4L, 2L));
        when(userService.getUserById(3L)).thenReturn(buildUser(3L, "agent02", UserRole.AGENT));

        CommentCreateRequest request = new CommentCreateRequest();
        request.setContent("我来帮忙看看");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.createComment(3L, 3L, request));

        assertTrue(ex.getMessage().contains("只能评论自己负责的工单"));
        verify(commentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("管理员可以评论任何工单")
    void shouldAllowAdminCommentAnyTicket() {
        when(ticketMapper.selectById(4L)).thenReturn(buildTicket(4L, 4L, 2L));
        when(userService.getUserById(1L)).thenReturn(buildUser(1L, "admin", UserRole.ADMIN));
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            Comment c = invocation.getArgument(0);
            c.setId(10L);
            return 1;
        });

        CommentCreateRequest request = new CommentCreateRequest();
        request.setContent("管理员备注");

        Comment result = commentService.createComment(4L, 1L, request);

        assertEquals(10L, result.getId());
        assertEquals("admin", result.getUsername());
    }

    @Test
    @DisplayName("工单不存在时拒绝评论")
    void shouldRejectWhenTicketNotFound() {
        when(ticketMapper.selectById(99L)).thenReturn(null);

        CommentCreateRequest request = new CommentCreateRequest();
        request.setContent("测试");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.createComment(99L, 4L, request));

        assertTrue(ex.getMessage().contains("工单不存在"));
        verify(commentMapper, never()).insert(any());
    }

    // ==================== 删除评论 ====================

    @Test
    @DisplayName("评论人本人可以删除自己的评论")
    void shouldAllowOwnerDeleteOwnComment() {
        Comment comment = new Comment();
        comment.setId(1L);
        comment.setUserId(4L);
        when(commentMapper.selectById(1L)).thenReturn(comment);

        commentService.deleteComment(1L, 4L, "CUSTOMER");

        verify(commentMapper).deleteById(1L);
    }

    @Test
    @DisplayName("管理员可以删除任何评论")
    void shouldAllowAdminDeleteAnyComment() {
        Comment comment = new Comment();
        comment.setId(2L);
        comment.setUserId(4L);
        when(commentMapper.selectById(2L)).thenReturn(comment);

        commentService.deleteComment(2L, 1L, "ADMIN");

        verify(commentMapper).deleteById(2L);
    }

    @Test
    @DisplayName("非评论人且非管理员不能删除评论")
    void shouldRejectDeleteByOtherUser() {
        Comment comment = new Comment();
        comment.setId(3L);
        comment.setUserId(4L);
        when(commentMapper.selectById(3L)).thenReturn(comment);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.deleteComment(3L, 5L, "CUSTOMER"));

        assertTrue(ex.getMessage().contains("只能删除自己发表的评论"));
        verify(commentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("评论不存在时拒绝删除")
    void shouldRejectDeleteNonExistentComment() {
        when(commentMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.deleteComment(99L, 4L, "CUSTOMER"));

        assertTrue(ex.getMessage().contains("评论不存在"));
    }
}
