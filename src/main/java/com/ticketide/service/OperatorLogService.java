package com.ticketide.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketide.entity.OperatorLog;

public interface OperatorLogService {

    void saveLog(Long ticketId, Long operatorId, String action,
                 String beforeStatus, String afterStatus, String remark);

    /**
     * 异步写操作日志（由 @LogRecord 切面调用）。
     * 独立线程池执行，不阻塞工单流转主流程；方法内部自行处理异常，日志失败不影响业务。
     */
    void saveLogAsync(Long ticketId, Long operatorId, String action,
                      String beforeStatus, String afterStatus, String remark);

    IPage<OperatorLog> getLogsByTicketId(Long ticketId, Page<OperatorLog> page);

    IPage<OperatorLog> getLogsByOperatorId(Long operatorId, Page<OperatorLog> page);
}
