-- ============================================================
-- TickeTide - 轻量级工单管理系统 数据库初始化脚本
-- 数据库: MySQL 8.0+
-- 字符集: utf8mb4
-- ============================================================

-- 创建数据库
DROP DATABASE IF EXISTS ticketide;
CREATE DATABASE ticketide DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ticketide;

-- ============================================================
-- 1. 用户表 t_user
-- ============================================================
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(50)  NOT NULL COMMENT '用户名',
    password    VARCHAR(100) NOT NULL COMMENT '密码',
    email       VARCHAR(100) NOT NULL COMMENT '邮箱',
    role        VARCHAR(20)  NOT NULL COMMENT '角色: ADMIN-管理员, AGENT-经办人, CUSTOMER-客户',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    KEY idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================================
-- 2. 工单表 t_ticket
-- ============================================================
DROP TABLE IF EXISTS t_ticket;
CREATE TABLE t_ticket (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '工单ID',
    title       VARCHAR(200) NOT NULL COMMENT '工单标题',
    description TEXT         COMMENT '工单描述',
    priority    VARCHAR(20)  NOT NULL COMMENT '优先级: HIGH-高, MEDIUM-中, LOW-低',
    status      VARCHAR(20)  NOT NULL COMMENT '状态: PENDING-待接单, ASSIGNED-已分配, PROCESSING-处理中, RESOLVED-已解决, PENDING_CONFIRM-待确认, REOPENED-已重开, CLOSED-已关闭, CANCELLED-已取消',
    customer_id BIGINT       NOT NULL COMMENT '创建人(客户)ID',
    agent_id    BIGINT       NULL COMMENT '经办人ID',
    version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_priority (priority),
    KEY idx_customer_id (customer_id),
    KEY idx_agent_id (agent_id),
    CONSTRAINT fk_ticket_customer FOREIGN KEY (customer_id) REFERENCES t_user (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_ticket_agent FOREIGN KEY (agent_id) REFERENCES t_user (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单表';

-- ============================================================
-- 3. 工单操作日志表 t_ticket_log
-- ============================================================
DROP TABLE IF EXISTS t_ticket_log;
CREATE TABLE t_ticket_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    ticket_id     BIGINT       NOT NULL COMMENT '工单ID',
    operator_id   BIGINT       NULL COMMENT '操作人ID（NULL 表示系统自动操作，如超时自动关闭）',
    action        VARCHAR(50)  NOT NULL COMMENT '操作动作: CREATE-创建, ASSIGN-派单, TAKE/GRAB-抢单, START_PROCESS-开始处理, RESOLVE-解决, CLOSE-关闭, REOPEN-重开, CANCEL-取消, TRANSFER-转交, ESCALATE-升级, UPDATE-更新',
    before_status VARCHAR(20)  NULL COMMENT '变更前状态',
    after_status  VARCHAR(20)  NULL COMMENT '变更后状态',
    remark        VARCHAR(500) NULL COMMENT '备注',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_ticket_id (ticket_id),
    KEY idx_operator_id (operator_id),
    CONSTRAINT fk_log_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_log_operator FOREIGN KEY (operator_id) REFERENCES t_user (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单操作日志表';

-- ============================================================
-- 4. 工单评论表 t_ticket_comment
-- ============================================================
DROP TABLE IF EXISTS t_ticket_comment;
CREATE TABLE t_ticket_comment (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    ticket_id   BIGINT       NOT NULL COMMENT '工单ID',
    user_id     BIGINT       NOT NULL COMMENT '评论人ID',
    content     TEXT         NOT NULL COMMENT '评论内容',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    PRIMARY KEY (id),
    KEY idx_ticket_id (ticket_id),
    KEY idx_user_id (user_id),
    KEY idx_create_time (create_time),
    CONSTRAINT fk_comment_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES t_user (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单评论表';

-- ============================================================
-- 5. 工单满意度评价表 t_ticket_rating
-- ============================================================
DROP TABLE IF EXISTS t_ticket_rating;
CREATE TABLE t_ticket_rating (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评价ID',
    ticket_id   BIGINT       NOT NULL COMMENT '工单ID',
    customer_id BIGINT       NOT NULL COMMENT '评价人（工单创建人）ID',
    agent_id    BIGINT       NULL COMMENT '被评价的客服ID',
    score       TINYINT      NOT NULL COMMENT '评分 1-5 星',
    content     VARCHAR(500) NULL COMMENT '评价内容',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_id (ticket_id) COMMENT '唯一索引：每个工单只能评价一次（并发兜底）',
    KEY idx_agent_id (agent_id),
    KEY idx_create_time (create_time),
    CONSTRAINT fk_rating_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_rating_customer FOREIGN KEY (customer_id) REFERENCES t_user (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单满意度评价表';

-- ============================================================
-- 初始测试数据
-- ============================================================

-- ------------------------------------------------------------
-- 1. 用户数据 (密码使用 BCrypt 加密, 明文均为 123456)
-- ------------------------------------------------------------
INSERT INTO t_user (username, password, email, role) VALUES
('admin',    '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', 'admin@ticketide.com',    'ADMIN'),
('agent01',  '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', 'agent01@ticketide.com',  'AGENT'),
('agent02',  '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', 'agent02@ticketide.com',  'AGENT'),
('customer01','$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6','customer01@ticketide.com','CUSTOMER'),
('customer02','$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6','customer02@ticketide.com','CUSTOMER');

-- ------------------------------------------------------------
-- 2. 工单数据
-- ------------------------------------------------------------
INSERT INTO t_ticket (title, description, priority, status, customer_id, agent_id, create_time, update_time) VALUES
-- 工单1: 待接单 (客户01创建, 无经办人)
('登录页面无法打开',
 '用户反馈登录页面访问返回502错误，需要紧急排查',
 'HIGH', 'PENDING', 4, NULL,
 '2026-08-01 09:30:00', '2026-08-01 09:30:00'),

-- 工单2: 已分配 (客户01创建, 经办人01处理)
('订单导出功能异常',
 '点击导出按钮后页面无响应，控制台报错NullPointerException',
 'MEDIUM', 'ASSIGNED', 4, 2,
 '2026-08-02 10:15:00', '2026-08-02 11:00:00'),

-- 工单3: 处理中 (客户02创建, 经办人01处理)
('支付接口超时',
 '支付宝回调接口偶发超时，导致订单状态不一致',
 'HIGH', 'PROCESSING', 5, 2,
 '2026-08-03 14:20:00', '2026-08-03 16:30:00'),

-- 工单4: 待确认 (客户02创建, 经办人02已解决，等待客户确认)
('首页加载缓慢',
 '首页首屏加载时间超过5秒，需要优化资源加载',
 'LOW', 'PENDING_CONFIRM', 5, 3,
 '2026-08-04 08:00:00', '2026-08-04 17:05:00'),

-- 工单5: 已关闭 (客户01创建, 经办人02处理)
('短信验证码收不到',
 '用户注册时短信验证码延迟到达，约5-10分钟',
 'MEDIUM', 'CLOSED', 4, 3,
 '2026-07-28 11:00:00', '2026-07-30 15:00:00'),

-- 工单6: 已重开 (客户02创建, 经办人01处理)
('商品搜索结果不准确',
 '搜索关键词"手机"返回了大量手机壳，排序逻辑有问题',
 'MEDIUM', 'REOPENED', 5, 2,
 '2026-07-25 09:00:00', '2026-08-04 10:00:00'),

-- 工单7: 待接单 (客户02创建, 无经办人)
('App闪退问题',
 'iOS版本App在商品详情页偶发闪退，频率约每天3-5次',
 'HIGH', 'PENDING', 5, NULL,
 '2026-08-05 08:45:00', '2026-08-05 08:45:00'),

-- 工单8: 处理中 (客户01创建, 经办人02处理)
('优惠券无法使用',
 '满减优惠券在结算时提示"已过期"，但券仍在有效期内',
 'LOW', 'PROCESSING', 4, 3,
 '2026-08-04 16:00:00', '2026-08-05 09:00:00');

-- ------------------------------------------------------------
-- 3. 工单操作日志数据
-- ------------------------------------------------------------
INSERT INTO t_ticket_log (ticket_id, operator_id, action, before_status, after_status, remark, create_time) VALUES
-- 工单1日志
(1, 4, 'CREATE', NULL, 'PENDING', '创建工单', '2026-08-01 09:30:00'),

-- 工单2日志
(2, 4, 'CREATE', NULL, 'PENDING', '创建工单', '2026-08-02 10:15:00'),
(2, 1, 'ASSIGN', 'PENDING', 'ASSIGNED', '管理员派单给agent01', '2026-08-02 11:00:00'),

-- 工单3日志
(3, 5, 'CREATE', NULL, 'PENDING', '创建工单', '2026-08-03 14:20:00'),
(3, 1, 'ASSIGN', 'PENDING', 'ASSIGNED', '管理员派单给agent01', '2026-08-03 14:30:00'),
(3, 2, 'START_PROCESS', 'ASSIGNED', 'PROCESSING', 'agent01开始处理', '2026-08-03 16:30:00'),

-- 工单4日志
(4, 5, 'CREATE', NULL, 'PENDING', '创建工单', '2026-08-04 08:00:00'),
(4, 1, 'ASSIGN', 'PENDING', 'ASSIGNED', '管理员派单给agent02', '2026-08-04 08:30:00'),
(4, 3, 'START_PROCESS', 'ASSIGNED', 'PROCESSING', 'agent02开始处理', '2026-08-04 09:00:00'),
(4, 3, 'RESOLVE', 'PROCESSING', 'RESOLVED', '已优化首页资源加载，首屏时间降至2秒', '2026-08-04 17:00:00'),
(4, 3, 'SUBMIT_CONFIRM', 'RESOLVED', 'PENDING_CONFIRM', '已解决，提交客户确认', '2026-08-04 17:05:00'),

-- 工单5日志
(5, 4, 'CREATE', NULL, 'PENDING', '创建工单', '2026-07-28 11:00:00'),
(5, 1, 'ASSIGN', 'PENDING', 'ASSIGNED', '管理员派单给agent02', '2026-07-28 11:30:00'),
(5, 3, 'START_PROCESS', 'ASSIGNED', 'PROCESSING', 'agent02开始处理', '2026-07-28 14:00:00'),
(5, 3, 'RESOLVE', 'PROCESSING', 'RESOLVED', '已修复短信通道配置问题', '2026-07-30 10:00:00'),
(5, 4, 'CLOSE', 'RESOLVED', 'CLOSED', '客户确认问题已解决', '2026-07-30 15:00:00'),

-- 工单6日志
(6, 5, 'CREATE', NULL, 'PENDING', '创建工单', '2026-07-25 09:00:00'),
(6, 1, 'ASSIGN', 'PENDING', 'ASSIGNED', '管理员派单给agent01', '2026-07-25 09:30:00'),
(6, 2, 'START_PROCESS', 'ASSIGNED', 'PROCESSING', 'agent01开始处理', '2026-07-25 10:00:00'),
(6, 2, 'RESOLVE', 'PROCESSING', 'RESOLVED', '已调整搜索排序权重', '2026-07-26 16:00:00'),
(6, 5, 'REOPEN', 'RESOLVED', 'REOPENED', '客户反馈问题仍存在，重新打开', '2026-08-04 10:00:00'),

-- 工单7日志
(7, 5, 'CREATE', NULL, 'PENDING', '创建工单', '2026-08-05 08:45:00'),

-- 工单8日志
(8, 4, 'CREATE', NULL, 'PENDING', '创建工单', '2026-08-04 16:00:00'),
(8, 4, 'GRAB', 'PENDING', 'PROCESSING', 'agent02抢单成功', '2026-08-04 16:30:00'),
(8, 3, 'UPDATE', 'PROCESSING', 'PROCESSING', '更新工单信息', '2026-08-05 09:00:00');

-- ============================================================
-- 验证数据
-- ============================================================
SELECT '===== 用户数据 =====' AS info;
SELECT id, username, email, role FROM t_user;

SELECT '===== 工单数据 =====' AS info;
SELECT id, title, priority, status, customer_id, agent_id FROM t_ticket;

SELECT '===== 操作日志数据 =====' AS info;
SELECT id, ticket_id, operator_id, action, before_status, after_status FROM t_ticket_log;

SELECT '===== 数据统计 =====' AS info;
SELECT
    (SELECT COUNT(*) FROM t_user)       AS user_count,
    (SELECT COUNT(*) FROM t_ticket)     AS ticket_count,
    (SELECT COUNT(*) FROM t_ticket_log) AS log_count;
