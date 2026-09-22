CREATE DATABASE IF NOT EXISTS ticketide DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ticketide;

CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码',
    email VARCHAR(100) COMMENT '邮箱',
    role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER' COMMENT '角色 ADMIN/AGENT/CUSTOMER',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_username (username) COMMENT '用户名索引',
    INDEX idx_email (email) COMMENT '邮箱索引',
    INDEX idx_role (role) COMMENT '角色索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS t_ticket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '工单ID',
    title VARCHAR(200) NOT NULL COMMENT '工单标题',
    description TEXT COMMENT '工单描述',
    priority VARCHAR(20) NOT NULL DEFAULT 'LOW' COMMENT '优先级 HIGH/MEDIUM/LOW',
    category VARCHAR(20) NOT NULL DEFAULT 'TECHNICAL' COMMENT '问题分类 TECHNICAL/ACCOUNT/COMPLAINT',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/ASSIGNED/PROCESSING/RESOLVED/PENDING_CONFIRM/REOPENED/CLOSED/CANCELLED',
    customer_id BIGINT NOT NULL COMMENT '客户ID',
    agent_id BIGINT COMMENT '代理ID',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status (status) COMMENT '状态索引',
    INDEX idx_priority (priority) COMMENT '优先级索引',
    INDEX idx_category (category) COMMENT '分类索引',
    INDEX idx_customer_id (customer_id) COMMENT '客户ID索引',
    INDEX idx_agent_id (agent_id) COMMENT '代理ID索引',
    INDEX idx_create_time (create_time) COMMENT '创建时间索引',
    CONSTRAINT fk_ticket_customer FOREIGN KEY (customer_id) REFERENCES t_user(id) ON DELETE CASCADE COMMENT '客户外键',
    CONSTRAINT fk_ticket_agent FOREIGN KEY (agent_id) REFERENCES t_user(id) ON DELETE SET NULL COMMENT '代理外键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单表';

CREATE TABLE IF NOT EXISTS t_ticket_attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '附件ID',
    ticket_id BIGINT NOT NULL COMMENT '工单ID',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_path VARCHAR(500) NOT NULL COMMENT '存储路径',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    content_type VARCHAR(100) COMMENT 'MIME类型',
    uploader_id BIGINT NOT NULL COMMENT '上传人ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    INDEX idx_ticket_id (ticket_id) COMMENT '工单ID索引',
    INDEX idx_uploader_id (uploader_id) COMMENT '上传人ID索引',
    INDEX idx_create_time (create_time) COMMENT '上传时间索引',
    CONSTRAINT fk_attachment_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket(id) ON DELETE CASCADE COMMENT '工单外键',
    CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES t_user(id) ON DELETE CASCADE COMMENT '上传人外键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单附件表';

CREATE TABLE IF NOT EXISTS t_ticket_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
    ticket_id BIGINT NOT NULL COMMENT '工单ID',
    operator_id BIGINT COMMENT '操作人ID',
    action VARCHAR(50) NOT NULL COMMENT '操作类型',
    before_status VARCHAR(20) COMMENT '操作前状态',
    after_status VARCHAR(20) COMMENT '操作后状态',
    remark VARCHAR(500) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_ticket_id (ticket_id) COMMENT '工单ID索引',
    INDEX idx_operator_id (operator_id) COMMENT '操作人ID索引',
    INDEX idx_create_time (create_time) COMMENT '操作时间索引',
    CONSTRAINT fk_ticket_log_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket(id) ON DELETE CASCADE COMMENT '工单外键',
    CONSTRAINT fk_ticket_log_operator FOREIGN KEY (operator_id) REFERENCES t_user(id) ON DELETE SET NULL COMMENT '操作人外键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单操作日志表';

CREATE TABLE IF NOT EXISTS t_ticket_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '评论ID',
    ticket_id BIGINT NOT NULL COMMENT '工单ID',
    user_id BIGINT NOT NULL COMMENT '评论人ID',
    content TEXT NOT NULL COMMENT '评论内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    INDEX idx_ticket_id (ticket_id) COMMENT '工单ID索引',
    INDEX idx_user_id (user_id) COMMENT '用户ID索引',
    INDEX idx_create_time (create_time) COMMENT '评论时间索引',
    CONSTRAINT fk_comment_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket(id) ON DELETE CASCADE COMMENT '工单外键',
    CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES t_user(id) ON DELETE CASCADE COMMENT '用户外键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单评论表';

CREATE TABLE IF NOT EXISTS t_ticket_rating (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '评价ID',
    ticket_id BIGINT NOT NULL COMMENT '工单ID',
    customer_id BIGINT NOT NULL COMMENT '评价人（工单创建人）ID',
    agent_id BIGINT COMMENT '被评价的客服ID',
    score TINYINT NOT NULL COMMENT '评分 1-5 星',
    content VARCHAR(500) COMMENT '评价内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    UNIQUE KEY uk_ticket_id (ticket_id) COMMENT '唯一索引：每个工单只能评价一次（并发兜底）',
    INDEX idx_agent_id (agent_id) COMMENT '客服ID索引',
    INDEX idx_create_time (create_time) COMMENT '评价时间索引',
    CONSTRAINT fk_rating_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket(id) ON DELETE CASCADE COMMENT '工单外键',
    CONSTRAINT fk_rating_customer FOREIGN KEY (customer_id) REFERENCES t_user(id) ON DELETE CASCADE COMMENT '评价人外键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单满意度评价表';


INSERT INTO t_user (username, password, email, role) VALUES
('admin', '123456', 'admin@ticketide.com', 'ADMIN'),
('agent001', '123456', 'agent001@ticketide.com', 'AGENT'),
('customer001', '123456', 'customer001@ticketide.com', 'CUSTOMER'),
('customer002', '123456', 'customer002@ticketide.com', 'CUSTOMER');
