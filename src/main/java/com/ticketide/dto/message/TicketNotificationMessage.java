package com.ticketide.dto.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工单通知消息（异步邮件/站内信内容）。
 * receiverEmail 兼容三种收件人：客户（受理/解决通知）、经办客服（客户驳回通知）、主管（升级/超时提醒）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketNotificationMessage {

    /**
     * 消息唯一ID：消费端据此做幂等，防止重复投递导致重复发邮件
     */
    private String messageId;

    private Long ticketId;

    private String receiverEmail;

    private String ticketTitle;

    private String status;

    private String statusDesc;
}
