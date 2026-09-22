package com.ticketide.controller;

import com.ticketide.annotation.CurrentUser;
import com.ticketide.annotation.RequiresRole;
import com.ticketide.dto.request.RatingCreateRequest;
import com.ticketide.dto.response.RatingResponse;
import com.ticketide.dto.response.Result;
import com.ticketide.enums.UserRole;
import com.ticketide.service.RatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 满意度评价。查询评价随工单详情一起返回，避免重复实现数据权限校验。
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class RatingController {

    private final RatingService ratingService;

    @PostMapping("/{id}/rating")
    @RequiresRole({UserRole.CUSTOMER})
    public Result<RatingResponse> rate(
            @PathVariable Long id,
            @Valid @RequestBody RatingCreateRequest request,
            @CurrentUser Long operatorId) {
        return Result.success(ratingService.rate(id, request, operatorId));
    }
}
