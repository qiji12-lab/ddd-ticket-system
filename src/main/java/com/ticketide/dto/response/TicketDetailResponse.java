package com.ticketide.dto.response;

import com.ticketide.entity.OperatorLog;
import com.ticketide.entity.Ticket;
import com.ticketide.util.DataMaskUtil;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TicketDetailResponse {

    private Long id;
    private String title;
    private String description;
    private String priority;
    private String priorityDesc;
    private String category;
    private String categoryDesc;
    private String status;
    private String statusDesc;
    private Long customerId;
    private Long agentId;
    private String customerName;
    private String agentName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<LogResponse> logs;
    private List<AttachmentResponse> attachments;
    private RatingResponse rating;

    @Data
    public static class LogResponse {
        private Long id;
        private String action;
        private String actionDesc;
        private String beforeStatus;
        private String afterStatus;
        private String remark;
        private Long operatorId;
        private String operatorName;
        private LocalDateTime createTime;

        public static LogResponse fromEntity(OperatorLog log) {
            LogResponse response = new LogResponse();
            response.setId(log.getId());
            response.setAction(log.getAction());
            response.setBeforeStatus(log.getBeforeStatus());
            response.setAfterStatus(log.getAfterStatus());
            response.setRemark(DataMaskUtil.mask(log.getRemark()));
            response.setOperatorId(log.getOperatorId());
            response.setCreateTime(log.getCreateTime());
            response.setActionDesc(mapActionDesc(log.getAction()));
            return response;
        }

        private static String mapActionDesc(String action) {
            return switch (action) {
                case "CREATE" -> "创建工单";
                case "ASSIGN" -> "管理员派单";
                case "TAKE", "GRAB" -> "抢单";
                case "START_PROCESS" -> "开始处理";
                case "RESOLVE" -> "处理完成";
                case "CLOSE" -> "关闭工单";
                case "REOPEN" -> "重新打开";
                case "UPDATE" -> "更新工单";
                case "UPLOAD_ATTACHMENT" -> "上传附件";
                default -> action;
            };
        }
    }

    public static TicketDetailResponse fromEntity(Ticket ticket, List<LogResponse> logs) {
        TicketDetailResponse response = new TicketDetailResponse();
        response.setId(ticket.getId());
        response.setTitle(ticket.getTitle());
        response.setDescription(ticket.getDescription());
        response.setPriority(ticket.getPriority());
        response.setCategory(ticket.getCategory());
        response.setStatus(ticket.getStatus());
        response.setCustomerId(ticket.getCustomerId());
        response.setAgentId(ticket.getAgentId());
        response.setCreateTime(ticket.getCreateTime());
        response.setUpdateTime(ticket.getUpdateTime());
        response.setLogs(logs);
        return response;
    }
}
