package com.ticketide.service.impl;

import com.ticketide.dto.message.TicketMessage;
import com.ticketide.dto.message.TicketNotificationMessage;
import com.ticketide.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    /**
     * 邮件服务开关：配置真实SMTP凭据后置为true启用真实发送，否则仅记录日志模拟
     */
    @Value("${spring.mail.enabled:false}")
    private boolean mailEnabled;

    @Override
    public void sendStatusChangeEmail(TicketNotificationMessage message) {
        if (!mailEnabled) {
            log.info("邮件服务未启用（spring.mail.enabled=false），模拟发送通知: to={}, 工单ID: {}",
                    message.getReceiverEmail(), message.getTicketId());
            return;
        }
        if (message.getReceiverEmail() == null || message.getReceiverEmail().isBlank()) {
            log.warn("收件人邮箱为空，跳过发送邮件: ticketId={}", message.getTicketId());
            return;
        }
        try {
            // 待确认状态下提示客户进行确认/重开操作
            String confirmTip = switch (message.getStatus() == null ? "" : message.getStatus()) {
                case "PENDING_CONFIRM" ->
                        "经办人已提交解决方案，请及时登录系统确认：满意则关闭工单，不满意可申请重新打开。\n\n";
                case "PROCESSING" -> "您的工单已被受理，客服正在处理中。\n\n";
                case "REOPENED" -> "客户对处理结果不满意，工单已重新打开，请尽快跟进处理。\n\n";
                default -> "";
            };
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(fromEmail);
            mail.setTo(message.getReceiverEmail());
            mail.setSubject(String.format("[TicketTide] 工单状态更新：%s", message.getTicketTitle()));
            mail.setText(String.format(
                    "您好，\n\n" +
                    "您的工单状态已更新！\n\n" +
                    "工单标题：%s\n" +
                    "工单编号：%d\n" +
                    "当前状态：%s\n\n" +
                    "%s" +
                    "感谢您使用 TicketTide 工单管理系统！\n\n" +
                    "TicketTide 工单管理系统",
                    message.getTicketTitle(),
                    message.getTicketId(),
                    message.getStatusDesc(),
                    confirmTip
            ));
            mailSender.send(mail);
            log.info("工单状态变更通知邮件发送成功: to={}, ticketId={}",
                    message.getReceiverEmail(), message.getTicketId());
        } catch (Exception e) {
            log.error("发送工单状态变更通知邮件失败: to={}, ticketId={}, 原因: {}",
                    message.getReceiverEmail(), message.getTicketId(), e.getMessage());
        }
    }

    @Override
    public void sendTicketCreatedEmail(TicketMessage message) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(fromEmail);
            mail.setTo(message.getCreatorEmail());
            mail.setSubject("[TicketTide] 工单创建成功");
            mail.setText(String.format(
                    "尊敬的 %s，\n\n" +
                    "您的工单已成功创建！\n\n" +
                    "工单标题：%s\n" +
                    "工单编号：%d\n" +
                    "工单描述：%s\n\n" +
                    "我们会尽快为您处理，请耐心等待。\n\n" +
                    "TicketTide 工单管理系统",
                    message.getCreatorName(),
                    message.getTitle(),
                    message.getTicketId(),
                    message.getDescription()
            ));
            mailSender.send(mail);
            log.info("工单创建通知邮件发送成功: {}", message.getCreatorEmail());
        } catch (Exception e) {
            log.error("发送工单创建通知邮件失败: {}", e.getMessage());
        }
    }

    @Override
    public void sendTicketAssignedEmail(TicketMessage message) {
        try {
            if (message.getAssigneeEmail() != null) {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(fromEmail);
                mail.setTo(message.getAssigneeEmail());
                mail.setSubject("[TicketTide] 工单已分配给您");
                mail.setText(String.format(
                        "尊敬的 %s，\n\n" +
                        "有新的工单已分配给您！\n\n" +
                        "工单标题：%s\n" +
                        "工单编号：%d\n" +
                        "工单描述：%s\n\n" +
                        "请尽快处理该工单。\n\n" +
                        "TicketTide 工单管理系统",
                        message.getAssigneeName(),
                        message.getTitle(),
                        message.getTicketId(),
                        message.getDescription()
                ));
                mailSender.send(mail);
                log.info("工单分配通知邮件发送成功: {}", message.getAssigneeEmail());
            }

            if (message.getCreatorEmail() != null) {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(fromEmail);
                mail.setTo(message.getCreatorEmail());
                mail.setSubject("[TicketTide] 工单已分配处理");
                mail.setText(String.format(
                        "尊敬的 %s，\n\n" +
                        "您的工单已分配给 %s 处理！\n\n" +
                        "工单标题：%s\n" +
                        "工单编号：%d\n\n" +
                        "处理人将尽快为您处理。\n\n" +
                        "TicketTide 工单管理系统",
                        message.getCreatorName(),
                        message.getAssigneeName(),
                        message.getTitle(),
                        message.getTicketId()
                ));
                mailSender.send(mail);
                log.info("工单分配通知邮件发送成功: {}", message.getCreatorEmail());
            }
        } catch (Exception e) {
            log.error("发送工单分配通知邮件失败: {}", e.getMessage());
        }
    }

    @Override
    public void sendTicketCompletedEmail(TicketMessage message) {
        try {
            if (message.getCreatorEmail() != null) {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(fromEmail);
                mail.setTo(message.getCreatorEmail());
                mail.setSubject("[TicketTide] 工单已解决");
                mail.setText(String.format(
                        "尊敬的 %s，\n\n" +
                        "您的工单已处理完成！\n\n" +
                        "工单标题：%s\n" +
                        "工单编号：%d\n" +
                        "处理人：%s\n\n" +
                        "感谢您使用 TicketTide 工单管理系统！\n\n" +
                        "TicketTide 工单管理系统",
                        message.getCreatorName(),
                        message.getTitle(),
                        message.getTicketId(),
                        message.getAssigneeName()
                ));
                mailSender.send(mail);
                log.info("工单完成通知邮件发送成功: {}", message.getCreatorEmail());
            }
        } catch (Exception e) {
            log.error("发送工单完成通知邮件失败: {}", e.getMessage());
        }
    }

    @Override
    public void sendTicketUpdatedEmail(TicketMessage message) {
        try {
            if (message.getCreatorEmail() != null) {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(fromEmail);
                mail.setTo(message.getCreatorEmail());
                mail.setSubject("[TicketTide] 工单状态更新");
                mail.setText(String.format(
                        "尊敬的 %s，\n\n" +
                        "您的工单状态已更新！\n\n" +
                        "工单标题：%s\n" +
                        "工单编号：%d\n" +
                        "当前状态：%s\n\n" +
                        "TicketTide 工单管理系统",
                        message.getCreatorName(),
                        message.getTitle(),
                        message.getTicketId(),
                        message.getStatus()
                ));
                mailSender.send(mail);
                log.info("工单更新通知邮件发送成功: {}", message.getCreatorEmail());
            }
        } catch (Exception e) {
            log.error("发送工单更新通知邮件失败: {}", e.getMessage());
        }
    }
}
