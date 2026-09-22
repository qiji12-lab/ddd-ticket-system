package com.ticketide.service.impl;

import com.ticketide.dto.request.RatingCreateRequest;
import com.ticketide.dto.response.RatingResponse;
import com.ticketide.entity.Ticket;
import com.ticketide.entity.TicketRating;
import com.ticketide.enums.TicketStatus;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.TicketMapper;
import com.ticketide.mapper.TicketRatingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 满意度评价业务规则测试：只有已关闭的工单能评价、只有创建人本人能评价、每单只能评价一次
 */
@ExtendWith(MockitoExtension.class)
class RatingServiceImplTest {

    @Mock
    private TicketRatingMapper ratingMapper;

    @Mock
    private TicketMapper ticketMapper;

    private RatingServiceImpl ratingService;

    @BeforeEach
    void setUp() {
        ratingService = new RatingServiceImpl(ratingMapper, ticketMapper);
    }

    private Ticket buildTicket(Long id, TicketStatus status, Long customerId, Long agentId) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setStatus(status.getCode());
        ticket.setCustomerId(customerId);
        ticket.setAgentId(agentId);
        return ticket;
    }

    @Test
    @DisplayName("评价成功：已关闭工单 + 创建人本人 + 未评价过")
    void shouldRateSuccessfully() {
        when(ticketMapper.selectById(1L)).thenReturn(buildTicket(1L, TicketStatus.CLOSED, 4L, 2L));
        when(ratingMapper.selectOne(any())).thenReturn(null);
        when(ratingMapper.insert(any(TicketRating.class))).thenReturn(1);

        RatingCreateRequest request = new RatingCreateRequest();
        request.setScore(5);
        request.setContent("处理很及时");

        RatingResponse response = ratingService.rate(1L, request, 4L);

        assertEquals(5, response.getScore());
        assertEquals("处理很及时", response.getContent());
        assertEquals(2L, response.getAgentId(), "评价应关联到被评价客服");
    }

    @Test
    @DisplayName("未关闭的工单不能评价")
    void shouldRejectWhenTicketNotClosed() {
        when(ticketMapper.selectById(2L)).thenReturn(buildTicket(2L, TicketStatus.PROCESSING, 4L, 2L));

        RatingCreateRequest request = new RatingCreateRequest();
        request.setScore(5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ratingService.rate(2L, request, 4L));

        assertTrue(ex.getMessage().contains("只有已关闭的工单才能评价"));
        verify(ratingMapper, never()).insert(any(TicketRating.class));
    }

    @Test
    @DisplayName("只能评价自己创建的工单")
    void shouldRejectWhenNotOwner() {
        when(ticketMapper.selectById(3L)).thenReturn(buildTicket(3L, TicketStatus.CLOSED, 4L, 2L));

        RatingCreateRequest request = new RatingCreateRequest();
        request.setScore(4);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ratingService.rate(3L, request, 5L));

        assertTrue(ex.getMessage().contains("只能评价自己创建的工单"));
    }

    @Test
    @DisplayName("重复评价被拦截：同一工单只能评价一次")
    void shouldRejectDuplicateRating() {
        when(ticketMapper.selectById(4L)).thenReturn(buildTicket(4L, TicketStatus.CLOSED, 4L, 2L));
        TicketRating existing = new TicketRating();
        existing.setTicketId(4L);
        existing.setScore(3);
        when(ratingMapper.selectOne(any())).thenReturn(existing);

        RatingCreateRequest request = new RatingCreateRequest();
        request.setScore(5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ratingService.rate(4L, request, 4L));

        assertTrue(ex.getMessage().contains("不能重复评价"));
        verify(ratingMapper, never()).insert(any(TicketRating.class));
    }

    @Test
    @DisplayName("并发重复评价：唯一索引冲突被翻译成业务异常")
    void shouldTranslateDuplicateKeyException() {
        when(ticketMapper.selectById(5L)).thenReturn(buildTicket(5L, TicketStatus.CLOSED, 4L, 2L));
        when(ratingMapper.selectOne(any())).thenReturn(null);
        when(ratingMapper.insert(any(TicketRating.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_ticket_id'"));

        RatingCreateRequest request = new RatingCreateRequest();
        request.setScore(5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ratingService.rate(5L, request, 4L));

        assertTrue(ex.getMessage().contains("不能重复评价"));
    }

    @Test
    @DisplayName("默认好评：已评价过的工单跳过，保证定时任务可重复执行")
    void shouldSkipDefaultRatingWhenAlreadyRated() {
        TicketRating existing = new TicketRating();
        existing.setTicketId(6L);
        when(ratingMapper.selectOne(any())).thenReturn(existing);

        ratingService.rateByDefault(6L, "系统超时自动关闭，默认好评");

        verify(ratingMapper, never()).insert(any(TicketRating.class));
    }
}
