package com.ticketide.service;

import com.ticketide.dto.response.AttachmentResponse;
import com.ticketide.entity.Attachment;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AttachmentService {

    /**
     * 上传附件：同步落盘 + 入库，并触发异步后处理
     */
    Attachment upload(MultipartFile file, Long ticketId, Long uploaderId);

    /**
     * 查询工单的附件列表
     */
    List<AttachmentResponse> listByTicketId(Long ticketId);

    /**
     * 删除附件（同时删除文件）
     */
    void delete(Long attachmentId, Long operatorId);

    /**
     * 获取附件（用于下载）
     */
    Attachment getById(Long attachmentId);
}
