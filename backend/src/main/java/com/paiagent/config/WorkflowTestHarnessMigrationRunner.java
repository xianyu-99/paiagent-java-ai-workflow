package com.paiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowTestHarnessMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public WorkflowTestHarnessMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS workflow_test_case (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    workflow_id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    input_data MEDIUMTEXT NOT NULL,
                    expected_contains JSON NULL,
                    expected_not_contains JSON NULL,
                    expected_status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
                    require_citation TINYINT NOT NULL DEFAULT 0,
                    require_audio TINYINT NOT NULL DEFAULT 0,
                    max_duration_ms INT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    INDEX idx_test_case_workflow (workflow_id),
                    INDEX idx_test_case_enabled (enabled)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='workflow test case'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS workflow_test_run (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    workflow_id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    total_count INT NOT NULL DEFAULT 0,
                    passed_count INT NOT NULL DEFAULT 0,
                    failed_count INT NOT NULL DEFAULT 0,
                    duration INT NOT NULL DEFAULT 0,
                    created_by BIGINT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    INDEX idx_test_run_workflow (workflow_id),
                    INDEX idx_test_run_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='workflow test run'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS workflow_test_result (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    run_id BIGINT NOT NULL,
                    case_id BIGINT NULL,
                    case_name VARCHAR(255) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    actual_output MEDIUMTEXT NULL,
                    assertion_results JSON NULL,
                    execution_id BIGINT NULL,
                    duration INT NULL,
                    error_message TEXT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    INDEX idx_test_result_run (run_id),
                    INDEX idx_test_result_case (case_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='workflow test result'
                """);

        log.info("workflow test harness tables are ready");
    }
}
