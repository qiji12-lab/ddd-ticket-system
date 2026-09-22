package com.ticketide.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketide.dto.request.TicketCreateRequest;
import com.ticketide.dto.request.TicketEscalateRequest;
import com.ticketide.dto.request.TicketQueryRequest;
import com.ticketide.dto.request.TicketStatusChangeRequest;
import com.ticketide.dto.request.TicketTransferRequest;
import com.ticketide.dto.request.TicketUpdateRequest;
import com.ticketide.dto.response.TicketDetailResponse;
import com.ticketide.entity.Ticket;

import java.util.List;

public interface TicketService {

    /**
     * 查询用户角色编码（ADMIN/AGENT/CUSTOMER），用户不存在返回 null
     */
    String getUserRole(Long userId);

    Ticket createTicket(TicketCreateRequest request, Long creatorId);

    Ticket getTicketById(Long id);

    TicketDetailResponse getTicketDetail(Long id);

    /**
     * 查询工单详情（带数据权限校验）：CUSTOMER 仅能查看自己创建的工单
     */
    TicketDetailResponse getTicketDetail(Long id, Long operatorId, String role);

    /**
     * 多条件分页查询工单（带数据权限范围）
     *
     * @param query      查询条件：状态、优先级、分类、关键词、提交时间范围、数据范围
     * @param operatorId 当前操作人ID
     * @param role       当前操作人角色：CUSTOMER 仅能看自己创建的；
     *                   AGENT 默认只能看"公共抢单池（待接单）+ 自己负责的"，scope=mine 时仅看自己负责的；
     *                   ADMIN 全部
     */
    IPage<Ticket> getTicketsByPage(Page<Ticket> page, TicketQueryRequest query, Long operatorId, String role);

    IPage<Ticket> getPendingTickets(Page<Ticket> page);

    IPage<Ticket> getTicketsByAssigneeId(Long assigneeId, Page<Ticket> page);

    IPage<Ticket> getTicketsByCreatorId(Long creatorId, Page<Ticket> page);

    Ticket updateTicket(Long id, TicketUpdateRequest request);

    Ticket updateTicketStatus(Long id, TicketStatusChangeRequest request, Long operatorId);

    Ticket assignTicket(Long id, Long assigneeId, Long operatorId);

    Ticket grabTicket(Long id, Long operatorId);

    /**
     * 取消工单：PENDING/PROCESSING -> CANCELLED，仅工单创建人本人或管理员可操作
     */
    Ticket cancelTicket(Long id, Long operatorId);

    /**
     * 转交工单：把经办人从当前客服变更给另一个客服（不改变工单状态）
     * 仅工单当前经办人或管理员可操作
     */
    Ticket transferTicket(Long id, TicketTransferRequest request, Long operatorId);

    /**
     * 升级工单：把优先级提升为 HIGH 并异步通知主管（管理员）
     * 仅工单当前经办人或管理员可操作
     */
    Ticket escalateTicket(Long id, TicketEscalateRequest request, Long operatorId);

    Ticket takeTicket(Long id, Long operatorId);

    Ticket startProcessing(Long id, Long operatorId);

    Ticket resolveTicket(Long id, Long operatorId);

    Ticket closeTicket(Long id, Long operatorId);

    Ticket reopenTicket(Long id, Long operatorId);

    void deleteTicket(Long id);

    /**
     * 待接单超时提醒：给所有主管发通知（同一工单 24 小时内只提醒一次）
     *
     * @return 本次实际提醒的工单数
     */
    int remindPendingTimeoutTickets(int hours);

    /**
     * 查询"待确认"超时的工单ID，供定时任务逐单调用 {@link #autoCloseTimeoutTicket(Long)}，
     * 逐单调用是为了让 @LogRecord 切面能生效（同类内部调用不走代理）
     */
    List<Long> findPendingConfirmTimeoutTicketIds(int hours);

    /**
     * 待确认超时：自动关闭工单并写入默认好评
     */
    Ticket autoCloseTimeoutTicket(Long id);
}
