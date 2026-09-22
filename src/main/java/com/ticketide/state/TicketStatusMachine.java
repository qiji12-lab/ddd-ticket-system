package com.ticketide.state;

import com.ticketide.entity.Ticket;
import com.ticketide.enums.TicketStatus;
import com.ticketide.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工单状态机：只做"流转合法性校验 + 状态变更"，不做任何持久化和日志记录。
 * <p>
 * 职责单一带来的好处：
 * 1) 状态规则集中在 {@link TicketStatus#canTransitionTo} 一张流转表里，Service 中不再出现 if-else 判断状态；
 * 2) 非法流转（如 待接单 -> 已解决）统一抛 {@link BusinessException}，由全局异常处理器转成 400；
 * 3) 日志记录完全交给 @LogRecord + AOP，状态机因此可以在单元测试里脱离数据库与日志依赖单独验证。
 */
@Component
@Slf4j
public class TicketStatusMachine {

    /**
     * 校验并执行状态流转
     *
     * @return 入参 ticket（状态已被更新为目标状态），便于链式调用
     * @throws BusinessException 目标状态与当前状态之间不存在合法流转路径
     */
    public Ticket changeStatus(Ticket ticket, TicketStatus targetStatus) {
        TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException(String.format("工单状态[%s]不能流转到[%s]",
                    currentStatus.getDesc(), targetStatus.getDesc()));
        }

        ticket.setStatus(targetStatus.getCode());
        log.debug("工单[{}]状态流转: {} -> {}", ticket.getId(), currentStatus.getDesc(), targetStatus.getDesc());
        return ticket;
    }
}
