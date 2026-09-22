package com.ticketide.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketide.entity.OperatorLog;
import com.ticketide.mapper.OperatorLogMapper;
import com.ticketide.service.OperatorLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorLogServiceImpl implements OperatorLogService {

    private final OperatorLogMapper operatorLogMapper;

    @Override
    public void saveLog(Long ticketId, Long operatorId, String action,
                        String beforeStatus, String afterStatus, String remark) {
        OperatorLog log = new OperatorLog();
        log.setTicketId(ticketId);
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setBeforeStatus(beforeStatus);
        log.setAfterStatus(afterStatus);
        log.setRemark(remark);
        operatorLogMapper.insert(log);
    }

    /**
     * 异步写日志：使用独立线程池 logExecutor，避免占用主流程线程；
     * 这里吞掉异常并打 error 日志——日志属于旁路能力，不能因为写日志失败而影响工单流转。
     * 注意：operatorId 为 null 表示系统自动操作（如待确认超时自动关闭），
     * 因此 t_ticket_log.operator_id 必须可空，否则插入会被数据库拒绝。
     */
    @Override
    @Async("logExecutor")
    public void saveLogAsync(Long ticketId, Long operatorId, String action,
                             String beforeStatus, String afterStatus, String remark) {
        try {
            saveLog(ticketId, operatorId, action, beforeStatus, afterStatus, remark);
        } catch (Exception e) {
            log.error("异步记录操作日志失败: ticketId={}, action={}, 原因: {}",
                    ticketId, action, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @Override
    public IPage<OperatorLog> getLogsByTicketId(Long ticketId, Page<OperatorLog> page) {
        return operatorLogMapper.selectByTicketId(page, ticketId);
    }

    @Override
    public IPage<OperatorLog> getLogsByOperatorId(Long operatorId, Page<OperatorLog> page) {
        return operatorLogMapper.selectByOperatorId(page, operatorId);
    }
}
