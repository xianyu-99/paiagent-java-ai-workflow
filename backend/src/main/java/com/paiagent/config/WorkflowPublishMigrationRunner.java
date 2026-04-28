package com.paiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowPublishMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public WorkflowPublishMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS workflow_publish (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    workflow_id BIGINT NOT NULL,
                    share_key VARCHAR(64) NOT NULL,
                    api_access_key VARCHAR(512) NULL,
                    title VARCHAR(255) NOT NULL,
                    description TEXT,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    created_by BIGINT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    UNIQUE KEY uk_publish_share_key (share_key),
                    UNIQUE KEY uk_publish_workflow_id (workflow_id),
                    INDEX idx_publish_enabled (enabled),
                    INDEX idx_publish_updated_at (updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='workflow publish'
                """);
        addColumnIfMissing(
                "api_access_key",
                "ALTER TABLE workflow_publish ADD COLUMN api_access_key VARCHAR(512) NULL AFTER share_key"
        );
        log.info("workflow_publish table is ready");
    }

    private void addColumnIfMissing(String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'workflow_publish'
                  AND column_name = ?
                """,
                Integer.class,
                columnName
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
