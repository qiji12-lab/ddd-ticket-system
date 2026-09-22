package com.ticketide.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TicketCreateRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题长度不能超过200个字符")
    private String title;

    @NotBlank(message = "描述不能为空")
    @Size(max = 2000, message = "描述长度不能超过2000个字符")
    private String description;

    @NotBlank(message = "优先级不能为空")
    @Pattern(regexp = "HIGH|MEDIUM|LOW", message = "优先级必须为 HIGH/MEDIUM/LOW")
    private String priority;

    @NotBlank(message = "问题分类不能为空")
    @Pattern(regexp = "TECHNICAL|ACCOUNT|COMPLAINT", message = "问题分类必须为 TECHNICAL/ACCOUNT/COMPLAINT")
    private String category;
}
