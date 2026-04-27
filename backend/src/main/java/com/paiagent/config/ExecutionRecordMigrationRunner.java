package com.paiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 执行记录表轻量迁移，兼容已有本地数据库。
 */
@Slf4j
@Component
public class ExecutionRecordMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public ExecutionRecordMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!tableExists()) {
            log.warn("execution_record 表不存在，跳过执行记录结构迁移");
            return;
        }
        addColumnIfMissing("error_log", "JSON NULL COMMENT '结构化错误日志'");
        addColumnIfMissing("retry_count", "INT DEFAULT 0 COMMENT '重试次数'");
        addColumnIfMissing("timeout_count", "INT DEFAULT 0 COMMENT '超时次数'");
    }

    private boolean tableExists() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'execution_record'
                """, Integer.class);
        return count != null && count > 0;
    }

    private void addColumnIfMissing(String columnName, String columnDefinition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'execution_record'
                  AND column_name = ?
                """, Integer.class, columnName);

        if (count != null && count > 0) {
            return;
        }

        String sql = "ALTER TABLE execution_record ADD COLUMN " + columnName + " " + columnDefinition;
        log.info("执行 execution_record 结构迁移: {}", sql);
        jdbcTemplate.execute(sql);
    }
}
