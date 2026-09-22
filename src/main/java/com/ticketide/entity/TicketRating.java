package com.ticketide.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工单满意度评价。
 * ticket_id 上有唯一索引，是"每单只能评价一次"这条业务规则的数据库兜底。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ticket_rating")
public class TicketRating {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Long customerId;

    private Long agentId;

    /**
     * 评分 1-5 星
     */
    private Integer score;

    private String content;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
