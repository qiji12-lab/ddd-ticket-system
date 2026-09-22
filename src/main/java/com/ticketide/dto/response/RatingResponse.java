package com.ticketide.dto.response;

import com.ticketide.entity.TicketRating;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RatingResponse {

    private Long id;
    private Long ticketId;
    private Long customerId;
    private Long agentId;
    private Integer score;
    private String content;
    private LocalDateTime createTime;

    public static RatingResponse fromEntity(TicketRating rating) {
        if (rating == null) {
            return null;
        }
        RatingResponse response = new RatingResponse();
        response.setId(rating.getId());
        response.setTicketId(rating.getTicketId());
        response.setCustomerId(rating.getCustomerId());
        response.setAgentId(rating.getAgentId());
        response.setScore(rating.getScore());
        response.setContent(rating.getContent());
        response.setCreateTime(rating.getCreateTime());
        return response;
    }
}
