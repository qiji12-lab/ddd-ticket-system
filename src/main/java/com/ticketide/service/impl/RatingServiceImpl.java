package com.ticketide.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketide.dto.request.RatingCreateRequest;
import com.ticketide.dto.response.RatingResponse;
import com.ticketide.entity.Ticket;
import com.ticketide.entity.TicketRating;
import com.ticketide.enums.TicketStatus;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.TicketMapper;
import com.ticketide.mapper.TicketRatingMapper;
import com.ticketide.service.RatingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class RatingServiceImpl implements RatingService {

    /**
     * 系统超时自动关闭时的默认评分
     */
    private static final int DEFAULT_SCORE = 5;

    private final TicketRatingMapper ratingMapper;
    private final TicketMapper ticketMapper;

    @Override
    @Transactional
    public RatingResponse rate(Long ticketId, RatingCreateRequest request, Long operatorId) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        // 业务规则校验（DDD 中属于领域规则，集中在这里，不散落到 Controller）
        // 1) 只有已关闭的工单才能评价
        if (TicketStatus.CLOSED != TicketStatus.fromCode(ticket.getStatus())) {
            throw new BusinessException("只有已关闭的工单才能评价");
        }
        // 2) 只有工单创建人本人可以评价（管理员不代评）
        if (!ticket.getCustomerId().equals(operatorId)) {
            throw new BusinessException("只能评价自己创建的工单");
        }
        // 3) 每个工单只能评价一次（先查一次给出友好提示，并发场景由唯一索引兜底）
        if (findByTicketId(ticketId) != null) {
            throw new BusinessException("该工单已评价，不能重复评价");
        }

        TicketRating rating = new TicketRating();
        rating.setTicketId(ticketId);
        rating.setCustomerId(operatorId);
        rating.setAgentId(ticket.getAgentId());
        rating.setScore(request.getScore());
        rating.setContent(StringUtils.hasText(request.getContent()) ? request.getContent().trim() : null);

        try {
            ratingMapper.insert(rating);
        } catch (DuplicateKeyException e) {
            // 两个请求同时通过上面的"未评价"校验时，唯一索引会让后到者插入失败；
            // 把数据库约束异常翻译成业务异常，保证"每单只能评价一次"不会被并发击穿
            log.warn("并发重复评价被唯一索引拦截: ticketId={}", ticketId);
            throw new BusinessException("该工单已评价，不能重复评价");
        }

        log.info("工单[{}]评价完成: score={}", ticketId, rating.getScore());
        return RatingResponse.fromEntity(rating);
    }

    @Override
    public RatingResponse getByTicketId(Long ticketId) {
        return RatingResponse.fromEntity(findByTicketId(ticketId));
    }

    @Override
    @Transactional
    public void rateByDefault(Long ticketId, String content) {
        // 定时任务可能被重复触发（多节点/重试），这里做成幂等：已评价则直接返回
        if (findByTicketId(ticketId) != null) {
            return;
        }
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            return;
        }
        TicketRating rating = new TicketRating();
        rating.setTicketId(ticketId);
        rating.setCustomerId(ticket.getCustomerId());
        rating.setAgentId(ticket.getAgentId());
        rating.setScore(DEFAULT_SCORE);
        rating.setContent(content);
        try {
            ratingMapper.insert(rating);
            log.info("工单[{}]待确认超时自动关闭，写入默认好评", ticketId);
        } catch (DuplicateKeyException e) {
            log.warn("默认好评写入冲突（已存在评价）: ticketId={}", ticketId);
        }
    }

    private TicketRating findByTicketId(Long ticketId) {
        return ratingMapper.selectOne(new LambdaQueryWrapper<TicketRating>()
                .eq(TicketRating::getTicketId, ticketId));
    }
}
