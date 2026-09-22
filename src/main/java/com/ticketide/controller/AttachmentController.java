package com.ticketide.controller;

import com.ticketide.annotation.CurrentUser;
import com.ticketide.annotation.RateLimit;
import com.ticketide.dto.response.AttachmentResponse;
import com.ticketide.dto.response.Result;
import com.ticketide.entity.Attachment;
import com.ticketide.entity.Ticket;
import com.ticketide.exception.BusinessException;
import com.ticketide.service.AttachmentService;
import com.ticketide.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/tickets/{ticketId}/attachments")
@RequiredArgsConstructor
@Slf4j
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final TicketService ticketService;

    /**
     * 上传附件（multipart/form-data）
     * - 同步落盘 + 入库，并触发 @Async 异步后续处理
     */
    @PostMapping
    @RateLimit(limit = 10, window = 60, keyType = RateLimit.KeyType.USER, message = "附件上传过于频繁，请1分钟后再试")
    public Result<AttachmentResponse> upload(
            @PathVariable Long ticketId,
            @RequestParam("file") MultipartFile file,
            @CurrentUser Long uploaderId) {
        // 权限校验：必须能访问该工单（通过 service 详情接口隐式校验）
        String role = ticketService.getUserRole(uploaderId);
        ticketService.getTicketDetail(ticketId, uploaderId, role);

        Attachment attachment = attachmentService.upload(file, ticketId, uploaderId);
        return Result.success(AttachmentResponse.fromEntity(attachment));
    }

    /**
     * 查询工单附件列表
     */
    @GetMapping
    public Result<List<AttachmentResponse>> list(@PathVariable Long ticketId, @CurrentUser Long operatorId) {
        String role = ticketService.getUserRole(operatorId);
        ticketService.getTicketDetail(ticketId, operatorId, role);
        return Result.success(attachmentService.listByTicketId(ticketId));
    }

    /**
     * 下载附件
     */
    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long ticketId,
            @PathVariable Long attachmentId,
            @CurrentUser Long operatorId) {
        String role = ticketService.getUserRole(operatorId);
        ticketService.getTicketDetail(ticketId, operatorId, role);

        Attachment attachment = attachmentService.getById(attachmentId);
        if (attachment == null || !attachment.getTicketId().equals(ticketId)) {
            throw new BusinessException("附件不存在");
        }

        File file = new File(attachment.getFilePath());
        if (!file.exists()) {
            throw new BusinessException("附件文件已被删除");
        }

        FileSystemResource resource = new FileSystemResource(file);
        String encodedName = URLEncoder.encode(attachment.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(resource);
    }

    /**
     * 删除附件（仅上传人或管理员可删）
     */
    @DeleteMapping("/{attachmentId}")
    public Result<Void> delete(
            @PathVariable Long ticketId,
            @PathVariable Long attachmentId,
            @CurrentUser Long operatorId) {
        String role = ticketService.getUserRole(operatorId);
        ticketService.getTicketDetail(ticketId, operatorId, role);

        Attachment attachment = attachmentService.getById(attachmentId);
        if (attachment == null || !attachment.getTicketId().equals(ticketId)) {
            throw new BusinessException("附件不存在");
        }
        if (!attachment.getUploaderId().equals(operatorId) && !"ADMIN".equals(role)) {
            throw new BusinessException("只能删除自己上传的附件");
        }

        attachmentService.delete(attachmentId, operatorId);
        return Result.success();
    }
}
