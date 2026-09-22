package com.ticketide.service;

import com.ticketide.dto.request.RatingCreateRequest;
import com.ticketide.dto.response.RatingResponse;

public interface RatingService {

    /**
     * 客户对已关闭的工单进行满意度评价（1-5 星）
     */
    RatingResponse rate(Long ticketId, RatingCreateRequest request, Long operatorId);

    /**
     * 查询工单评价，未评价返回 null
     */
    RatingResponse getByTicketId(Long ticketId);

    /**
     * 写入默认好评（工单待确认超时被系统自动关闭时调用）
     */
    void rateByDefault(Long ticketId, String content);
}
