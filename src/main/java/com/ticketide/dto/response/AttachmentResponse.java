package com.ticketide.dto.response;

import com.ticketide.entity.Attachment;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AttachmentResponse {

    private Long id;

    private Long ticketId;

    private String fileName;

    private Long fileSize;

    private String contentType;

    private Long uploaderId;

    private String uploaderName;

    private LocalDateTime createTime;

    public static AttachmentResponse fromEntity(Attachment attachment) {
        AttachmentResponse response = new AttachmentResponse();
        response.setId(attachment.getId());
        response.setTicketId(attachment.getTicketId());
        response.setFileName(attachment.getFileName());
        response.setFileSize(attachment.getFileSize());
        response.setContentType(attachment.getContentType());
        response.setUploaderId(attachment.getUploaderId());
        response.setUploaderName(attachment.getUploaderName());
        response.setCreateTime(attachment.getCreateTime());
        return response;
    }
}
