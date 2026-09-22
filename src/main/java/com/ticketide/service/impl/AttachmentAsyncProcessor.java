package com.ticketide.service.impl;

import com.ticketide.entity.Attachment;
import com.ticketide.service.OperatorLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 附件异步后处理器。
 *
 * 独立成 Bean 的原因：@Async 依赖 Spring 代理生效，
 * 若在 AttachmentServiceImpl 内部用 this 直接调用，会绕过代理退化为同步执行。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentAsyncProcessor {

    private final OperatorLogService operatorLogService;

    /**
     * 异步处理附件上传后的后续任务：
     * - 记录操作日志
     * - 后续可扩展：生成缩略图、病毒扫描、通知发送等
     */
    @Async("attachmentExecutor")
    public void postUploadProcess(Attachment attachment) {
        try {
            log.info("异步处理附件开始：ticketId={}, attachmentId={}, fileName={}, thread={}",
                    attachment.getTicketId(), attachment.getId(), attachment.getFileName(),
                    Thread.currentThread().getName());

            operatorLogService.saveLog(
                    attachment.getTicketId(),
                    attachment.getUploaderId(),
                    "UPLOAD_ATTACHMENT",
                    null,
                    null,
                    "上传附件: " + attachment.getFileName());

            log.info("异步处理附件完成：attachmentId={}", attachment.getId());
        } catch (Exception e) {
            log.error("异步处理附件失败：attachmentId={}", attachment.getId(), e);
        }
    }
}
