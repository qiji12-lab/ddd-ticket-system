package com.ticketide.aspect;

import com.ticketide.annotation.LogRecord;
import com.ticketide.dto.request.TicketEscalateRequest;
import com.ticketide.dto.request.TicketTransferRequest;
import com.ticketide.entity.User;
import com.ticketide.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 日志备注生成器：供 @LogRecord 的 remark 以 SpEL 形式调用（{@code @logRemarkHelper.transfer(#request)}）。
 * <p>
 * 把它独立成 Bean 的原因：备注里需要人名等动态信息，而注解只能写字符串表达式，
 * 通过 SpEL 调用 Bean 既能保持注解可读，又不需要在业务方法里拼字符串。
 */
@Component("logRemarkHelper")
@RequiredArgsConstructor
public class LogRemarkHelper {

    private final UserService userService;

    public String transfer(TicketTransferRequest request) {
        return "工单转交给客服[" + username(request.getTargetAgentId())
                + "]；原因：" + orDefault(request.getRemark(), "无");
    }

    public String escalate(TicketEscalateRequest request) {
        return "升级为高优先级并通知主管；原因：" + orDefault(request.getRemark(), "无");
    }

    public String username(Long userId) {
        if (userId == null) {
            return "未分配";
        }
        User user = userService.getUserById(userId);
        return user == null ? String.valueOf(userId) : user.getUsername();
    }

    public String orDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
