package com.ticketide.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketide.annotation.RateLimit;
import com.ticketide.annotation.RequiresRole;
import com.ticketide.dto.request.TicketCreateRequest;
import com.ticketide.dto.request.TicketEscalateRequest;
import com.ticketide.dto.request.TicketQueryRequest;
import com.ticketide.dto.request.TicketStatusChangeRequest;
import com.ticketide.dto.request.TicketTransferRequest;
import com.ticketide.dto.response.Result;
import com.ticketide.dto.response.TicketDetailResponse;
import com.ticketide.dto.response.TicketResponse;
import com.ticketide.entity.Ticket;
import com.ticketide.enums.UserRole;
import com.ticketide.service.TicketService;
import com.ticketide.annotation.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@Slf4j
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    @RequiresRole({UserRole.CUSTOMER})
    @RateLimit(limit = 5, window = 10, keyType = RateLimit.KeyType.USER, message = "创建工单过于频繁，请10秒后再试")
    public Result<TicketResponse> createTicket(
            @Valid @RequestBody TicketCreateRequest request,
            @CurrentUser Long creatorId) {
        Ticket ticket = ticketService.createTicket(request, creatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @GetMapping
    public Result<IPage<TicketResponse>> getTickets(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            TicketQueryRequest query,
            @CurrentUser Long operatorId) {
        // 按角色强制限定数据范围
        String role = ticketService.getUserRole(operatorId);
        Page<Ticket> page = new Page<>(pageNum, pageSize);
        IPage<Ticket> ticketPage = ticketService.getTicketsByPage(page, query, operatorId, role);
        IPage<TicketResponse> responsePage = ticketPage.convert(TicketResponse::fromEntity);
        return Result.success(responsePage);
    }

    @GetMapping("/{id}")
    public Result<TicketDetailResponse> getTicketDetail(
            @PathVariable Long id,
            @CurrentUser Long operatorId) {
        String role = ticketService.getUserRole(operatorId);
        TicketDetailResponse detail = ticketService.getTicketDetail(id, operatorId, role);
        return Result.success(detail);
    }

    @PutMapping("/{id}/status")
    public Result<TicketResponse> updateTicketStatus(
            @PathVariable Long id,
            @Valid @RequestBody TicketStatusChangeRequest request,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.updateTicketStatus(id, request, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/assign")
    @RequiresRole({UserRole.ADMIN})
    public Result<TicketResponse> assignTicket(
            @PathVariable Long id,
            @RequestParam Long assigneeId,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.assignTicket(id, assigneeId, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/grab")
    @RequiresRole({UserRole.AGENT})
    @RateLimit(limit = 3, window = 5, keyType = RateLimit.KeyType.USER, message = "抢单操作过于频繁，请5秒后再试")
    public Result<TicketResponse> grabTicket(
            @PathVariable Long id,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.grabTicket(id, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/take")
    @RequiresRole({UserRole.AGENT, UserRole.ADMIN})
    public Result<TicketResponse> takeTicket(
            @PathVariable Long id,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.takeTicket(id, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/start")
    @RequiresRole({UserRole.AGENT, UserRole.ADMIN})
    public Result<TicketResponse> startProcessing(
            @PathVariable Long id,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.startProcessing(id, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/resolve")
    @RequiresRole({UserRole.AGENT, UserRole.ADMIN})
    public Result<TicketResponse> resolveTicket(
            @PathVariable Long id,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.resolveTicket(id, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/close")
    @RequiresRole({UserRole.ADMIN, UserRole.CUSTOMER})
    public Result<TicketResponse> closeTicket(
            @PathVariable Long id,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.closeTicket(id, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/reopen")
    @RequiresRole({UserRole.CUSTOMER, UserRole.ADMIN})
    public Result<TicketResponse> reopenTicket(
            @PathVariable Long id,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.reopenTicket(id, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/cancel")
    @RequiresRole({UserRole.CUSTOMER, UserRole.ADMIN})
    public Result<TicketResponse> cancelTicket(
            @PathVariable Long id,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.cancelTicket(id, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/transfer")
    @RequiresRole({UserRole.AGENT, UserRole.ADMIN})
    public Result<TicketResponse> transferTicket(
            @PathVariable Long id,
            @Valid @RequestBody TicketTransferRequest request,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.transferTicket(id, request, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @PostMapping("/{id}/escalate")
    @RequiresRole({UserRole.AGENT, UserRole.ADMIN})
    public Result<TicketResponse> escalateTicket(
            @PathVariable Long id,
            @Valid @RequestBody TicketEscalateRequest request,
            @CurrentUser Long operatorId) {
        Ticket ticket = ticketService.escalateTicket(id, request, operatorId);
        return Result.success(TicketResponse.fromEntity(ticket));
    }

    @DeleteMapping("/{id}")
    @RequiresRole({UserRole.ADMIN})
    public Result<Void> deleteTicket(@PathVariable Long id) {
        ticketService.deleteTicket(id);
        return Result.success();
    }
}
