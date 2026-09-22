package com.ticketide.service.impl;

import com.ticketide.dto.response.AttachmentResponse;
import com.ticketide.entity.Attachment;
import com.ticketide.entity.User;
import com.ticketide.exception.BusinessException;
import com.ticketide.mapper.AttachmentMapper;
import com.ticketide.service.AttachmentService;
import com.ticketide.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentServiceImpl implements AttachmentService {

    private final AttachmentMapper attachmentMapper;
    private final UserService userService;
    private final AttachmentAsyncProcessor attachmentAsyncProcessor;

    @Value("${ticketide.upload-path:./uploads}")
    private String uploadPath;

    @Value("${ticketide.max-file-size:10485760}")
    private long maxFileSize;

    @Override
    @Transactional
    public Attachment upload(MultipartFile file, Long ticketId, Long uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > maxFileSize) {
            throw new BusinessException("文件大小超过限制（最大 " + (maxFileSize / 1024 / 1024) + "MB）");
        }

        User uploader = userService.getUserById(uploaderId);
        if (uploader == null) {
            throw new BusinessException("上传人不存在");
        }

        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        if (originalName.contains("..")) {
            throw new BusinessException("文件名非法");
        }

        // 按日期分目录 + UUID 防重名
        // 注意：必须使用绝对路径。MultipartFile#transferTo(File) 传相对路径时，
        // Servlet 容器会将其解析到自己的临时工作目录，导致落盘失败。
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String storedName = UUID.randomUUID() + getFileExtension(originalName);
        Path baseDir = Paths.get(uploadPath).toAbsolutePath().normalize();
        Path dirPath = baseDir.resolve(dateDir);
        Path filePath = dirPath.resolve(storedName);

        try {
            Files.createDirectories(dirPath);
            // 同步落盘：transferTo(Path) 直接流式写入目标路径，不经过容器临时目录
            file.transferTo(filePath);
            log.info("附件已落盘：{}", filePath);
        } catch (IOException e) {
            log.error("附件存储失败: ticketId={}, fileName={}, target={}", ticketId, originalName, filePath, e);
            throw new BusinessException("附件存储失败");
        }

        // 入库
        Attachment attachment = new Attachment();
        attachment.setTicketId(ticketId);
        attachment.setFileName(originalName);
        attachment.setFilePath(filePath.toString());
        attachment.setFileSize(file.getSize());
        attachment.setContentType(file.getContentType());
        attachment.setUploaderId(uploaderId);
        attachmentMapper.insert(attachment);

        // 委托独立 Bean 异步处理后续任务（日志、通知等），确保 @Async 走代理生效
        attachmentAsyncProcessor.postUploadProcess(attachment);

        return attachment;
    }

    @Override
    public List<AttachmentResponse> listByTicketId(Long ticketId) {
        List<Attachment> attachments = attachmentMapper.selectByTicketId(ticketId);
        return attachments.stream().map(a -> {
            AttachmentResponse response = AttachmentResponse.fromEntity(a);
            User uploader = userService.getUserById(a.getUploaderId());
            if (uploader != null) {
                response.setUploaderName(uploader.getUsername());
            }
            return response;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void delete(Long attachmentId, Long operatorId) {
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw new BusinessException("附件不存在");
        }

        // 删除磁盘文件
        try {
            File file = new File(attachment.getFilePath());
            if (file.exists() && !file.delete()) {
                log.warn("删除附件文件失败: {}", attachment.getFilePath());
            }
        } catch (Exception e) {
            log.error("删除附件文件异常: {}", attachment.getFilePath(), e);
        }

        attachmentMapper.deleteById(attachmentId);
    }

    @Override
    public Attachment getById(Long attachmentId) {
        return attachmentMapper.selectById(attachmentId);
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }
}
