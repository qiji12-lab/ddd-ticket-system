package com.ticketide.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketide.dto.response.Result;
import com.ticketide.dto.response.PageResponse;
import com.ticketide.entity.OperatorLog;
import com.ticketide.service.OperatorLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class OperatorLogController {

    private final OperatorLogService operatorLogService;

    @GetMapping("/ticket/{ticketId}")
    public Result<PageResponse<OperatorLog>> getLogsByTicket(
            @PathVariable Long ticketId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {

        IPage<OperatorLog> logPage = operatorLogService.getLogsByTicketId(ticketId, new Page<>(page, size));

        return Result.success(new PageResponse<>(
                logPage.getRecords(), logPage.getTotal(), size, page));
    }

    @GetMapping("/operator/{operatorId}")
    public Result<PageResponse<OperatorLog>> getLogsByOperator(
            @PathVariable Long operatorId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {

        IPage<OperatorLog> logPage = operatorLogService.getLogsByOperatorId(operatorId, new Page<>(page, size));

        return Result.success(new PageResponse<>(
                logPage.getRecords(), logPage.getTotal(), size, page));
    }
}
