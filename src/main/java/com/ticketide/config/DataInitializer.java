package com.ticketide.config;

import com.ticketide.entity.User;
import com.ticketide.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        migrateSchema();
        List<User> users = userMapper.selectList(null);
        boolean needUpdate = false;

        for (User user : users) {
            // 检查密码是否能正确校验，如果不能则重新生成
            try {
                if (!BCrypt.checkpw("123456", user.getPassword())) {
                    // 重新生成密码哈希
                    String newHash = BCrypt.hashpw("123456", BCrypt.gensalt());
                    user.setPassword(newHash);
                    userMapper.updateById(user);
                    needUpdate = true;
                    log.info("已重置用户[{}]的密码哈希", user.getUsername());
                }
            } catch (Exception e) {
                // 密码格式异常，重新生成
                String newHash = BCrypt.hashpw("123456", BCrypt.gensalt());
                user.setPassword(newHash);
                userMapper.updateById(user);
                needUpdate = true;
                log.info("已重置用户[{}]的密码哈希（格式异常修复）", user.getUsername());
            }
        }

        if (needUpdate) {
            log.info("密码初始化完成，所有用户密码已重置为: 123456");
        } else {
            log.info("密码校验正常，无需重置");
        }
    }

    /**
     * Schema 迁移：增量更新（幂等执行，已存在则跳过）
     * 1) t_ticket 加 category 字段
     * 2) 新建 t_ticket_attachment 表
     * 3) t_ticket 加 version 乐观锁字段
     * 4) 新建 t_ticket_rating 满意度评价表
     * 5) t_ticket_log.operator_id 改为可空（系统自动操作无操作人）
     */
    private void migrateSchema() {
        try {
            // 1. 检查 t_ticket 是否有 category 列
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = 'ticketide' AND TABLE_NAME = 't_ticket' AND COLUMN_NAME = 'category'",
                    Integer.class);
            if (cnt != null && cnt == 0) {
                jdbcTemplate.execute("ALTER TABLE t_ticket ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'TECHNICAL' COMMENT '问题分类 TECHNICAL/ACCOUNT/COMPLAINT' AFTER priority");
                jdbcTemplate.execute("CREATE INDEX idx_category ON t_ticket(category)");
                log.info("迁移：t_ticket 增加 category 字段");
            }

            // 2. 创建附件表
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS t_ticket_attachment (" +
                            "id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '附件ID', " +
                            "ticket_id BIGINT NOT NULL COMMENT '工单ID', " +
                            "file_name VARCHAR(255) NOT NULL COMMENT '原始文件名', " +
                            "file_path VARCHAR(500) NOT NULL COMMENT '存储路径', " +
                            "file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小(字节)', " +
                            "content_type VARCHAR(100) COMMENT 'MIME类型', " +
                            "uploader_id BIGINT NOT NULL COMMENT '上传人ID', " +
                            "create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间', " +
                            "INDEX idx_ticket_id (ticket_id), " +
                            "INDEX idx_uploader_id (uploader_id), " +
                            "INDEX idx_create_time (create_time), " +
                            "CONSTRAINT fk_attachment_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket(id) ON DELETE CASCADE, " +
                            "CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES t_user(id) ON DELETE CASCADE" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单附件表'");
            log.info("迁移检查完成：t_ticket_attachment 已存在或已创建");

            // 3. 检查 t_ticket 是否有 version 列（乐观锁）
            // 历史数据补默认值 0，保证老工单也能参与基于版本号的并发控制
            Integer versionCnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = 'ticketide' AND TABLE_NAME = 't_ticket' AND COLUMN_NAME = 'version'",
                    Integer.class);
            if (versionCnt != null && versionCnt == 0) {
                jdbcTemplate.execute("ALTER TABLE t_ticket ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER agent_id");
                log.info("迁移：t_ticket 增加 version 乐观锁字段");
            }

            // 4. operator_id 允许为空：定时任务（如待确认超时自动关闭）属于系统操作，没有操作人
            String operatorNullable = jdbcTemplate.queryForObject(
                    "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = 'ticketide' AND TABLE_NAME = 't_ticket_log' AND COLUMN_NAME = 'operator_id'",
                    String.class);
            if ("NO".equalsIgnoreCase(operatorNullable)) {
                jdbcTemplate.execute("ALTER TABLE t_ticket_log MODIFY COLUMN operator_id BIGINT NULL COMMENT '操作人ID（NULL 表示系统自动操作）'");
                log.info("迁移：t_ticket_log.operator_id 改为可空（支持系统自动操作日志）");
            }

            // 5. 创建满意度评价表（ticket_id 唯一索引保证"每单只能评价一次"）
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS t_ticket_rating (" +
                            "id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '评价ID', " +
                            "ticket_id BIGINT NOT NULL COMMENT '工单ID', " +
                            "customer_id BIGINT NOT NULL COMMENT '评价人（工单创建人）ID', " +
                            "agent_id BIGINT COMMENT '被评价的客服ID', " +
                            "score TINYINT NOT NULL COMMENT '评分 1-5 星', " +
                            "content VARCHAR(500) COMMENT '评价内容', " +
                            "create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间', " +
                            "UNIQUE KEY uk_ticket_id (ticket_id), " +
                            "INDEX idx_agent_id (agent_id), " +
                            "INDEX idx_create_time (create_time), " +
                            "CONSTRAINT fk_rating_ticket FOREIGN KEY (ticket_id) REFERENCES t_ticket(id) ON DELETE CASCADE, " +
                            "CONSTRAINT fk_rating_customer FOREIGN KEY (customer_id) REFERENCES t_user(id) ON DELETE CASCADE" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单满意度评价表'");
            log.info("迁移检查完成：t_ticket_rating 已存在或已创建");
        } catch (Exception e) {
            log.error("Schema 迁移失败", e);
        }
    }
}
