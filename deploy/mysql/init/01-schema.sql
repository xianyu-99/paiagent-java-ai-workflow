CREATE DATABASE IF NOT EXISTS paiagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE paiagent;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    UNIQUE KEY uk_app_user_username (username),
    INDEX idx_app_user_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    flow_data JSON NOT NULL,
    engine_type VARCHAR(50) DEFAULT 'dag',
    owner_id BIGINT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_owner_id (owner_id),
    INDEX idx_created_at (created_at),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS node_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    node_type VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    category VARCHAR(50) NOT NULL,
    icon VARCHAR(255),
    input_schema JSON,
    output_schema JSON,
    config_schema JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS execution_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    flow_id BIGINT NOT NULL,
    input_data JSON,
    output_data JSON,
    status VARCHAR(50) NOT NULL,
    node_results JSON,
    error_message TEXT,
    error_log JSON,
    retry_count INT DEFAULT 0,
    timeout_count INT DEFAULT 0,
    duration INT,
    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_flow_id (flow_id),
    INDEX idx_executed_at (executed_at),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    owner_id BIGINT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_kb_owner_id (owner_id),
    INDEX idx_kb_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    owner_id BIGINT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(150) NULL,
    parser_type VARCHAR(50) NULL,
    content_hash VARCHAR(64) NOT NULL,
    chunk_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_doc_kb_id (knowledge_base_id),
    INDEX idx_doc_owner_id (owner_id),
    INDEX idx_doc_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    source_name VARCHAR(255) NULL,
    content_type VARCHAR(150) NULL,
    section_title VARCHAR(500) NULL,
    page_number INT NULL,
    start_offset INT NULL,
    end_offset INT NULL,
    embedding JSON NOT NULL,
    embedding_provider VARCHAR(50) NULL,
    embedding_model VARCHAR(100) NULL,
    embedding_dimension INT NULL,
    token_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_chunk_kb_id (knowledge_base_id),
    INDEX idx_chunk_doc_id (document_id),
    INDEX idx_chunk_doc_page (document_id, page_number, chunk_index),
    INDEX idx_chunk_embedding_meta (knowledge_base_id, embedding_provider, embedding_model, embedding_dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_import_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    owner_id BIGINT NULL,
    document_id BIGINT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(150) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    stage VARCHAR(255) NULL,
    progress INT DEFAULT 0,
    total_chunks INT DEFAULT 0,
    processed_chunks INT DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    deleted TINYINT DEFAULT 0,
    INDEX idx_import_kb_id (knowledge_base_id),
    INDEX idx_import_owner_id (owner_id),
    INDEX idx_import_status (status),
    INDEX idx_import_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS llm_global_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    config_name VARCHAR(100) NOT NULL,
    api_url VARCHAR(255) NOT NULL,
    api_key TEXT NOT NULL,
    model VARCHAR(100) NOT NULL,
    temperature DECIMAL(3,2) DEFAULT 0.70,
    is_default TINYINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    UNIQUE KEY uk_provider_config_name (provider, config_name),
    INDEX idx_provider (provider),
    INDEX idx_is_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO node_definition (node_type, display_name, category, icon, input_schema, output_schema, config_schema) VALUES
('input', '输入', 'IO', '📥',
 '{"type":"object","properties":{}}',
 '{"type":"object","properties":{"input":{"type":"string"}}}',
 '{"type":"object","properties":{"defaultValue":{"type":"string"}}}'),
('output', '输出', 'IO', '📤',
 '{"type":"object","properties":{"input":{"type":"string"}}}',
 '{"type":"object","properties":{"output":{"type":"string"}}}',
 '{"type":"object","properties":{}}'),
('llm', '大模型', 'LLM', '🤖',
 '{"type":"object","properties":{"input":{"type":"string"}}}',
 '{"type":"object","properties":{"output":{"type":"string"},"tokens":{"type":"number"}}}',
 '{"type":"object","properties":{"provider":{"type":"string"},"configId":{"type":"number"},"apiKey":{"type":"string"},"model":{"type":"string"},"prompt":{"type":"string"},"temperature":{"type":"number","default":0.7},"maxTokens":{"type":"number","default":1000}}}'),
('tts', '超拟人音频合成', 'TOOL', '🔊',
 '{"type":"object","properties":{"text":{"type":"string"}}}',
 '{"type":"object","properties":{"audioUrl":{"type":"string"},"fileName":{"type":"string"},"output":{"type":"string"},"chunks":{"type":"number"}}}',
 '{"type":"object","properties":{"provider":{"type":"string","default":"qwen"},"apiUrl":{"type":"string"},"apiKey":{"type":"string"},"model":{"type":"string","default":"qwen3-tts-flash"},"voice":{"type":"string","default":"Cherry"},"style":{"type":"string"},"languageType":{"type":"string","default":"Auto"}}}'),
('condition', '条件分支', 'FLOW', '🔀',
 '{"type":"object","properties":{"input":{"type":"string"},"output":{"type":"string"}}}',
 '{"type":"object","properties":{"conditionResult":{"type":"boolean"},"selectedBranch":{"type":"string"},"output":{"type":"string"}}}',
 '{"type":"object","properties":{"leftType":{"type":"string","default":"reference"},"leftReference":{"type":"string"},"leftValue":{"type":"string"},"operator":{"type":"string","default":"equals"},"rightValue":{"type":"string"},"caseSensitive":{"type":"boolean","default":false}}}'),
('rag', '知识库问答', 'KNOWLEDGE', '📚',
 '{"type":"object","properties":{"question":{"type":"string"}}}',
 '{"type":"object","properties":{"output":{"type":"string"},"context":{"type":"string"},"retrievedChunks":{"type":"array"},"retrievedCount":{"type":"number"}}}',
 '{"type":"object","properties":{"knowledgeBaseId":{"type":"number"},"topK":{"type":"number","default":3},"minScore":{"type":"number","default":0},"contextWindow":{"type":"number","default":1},"contextMaxChars":{"type":"number","default":1800},"configId":{"type":"number"},"prompt":{"type":"string"}}}')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    category = VALUES(category),
    icon = VALUES(icon),
    input_schema = VALUES(input_schema),
    output_schema = VALUES(output_schema),
    config_schema = VALUES(config_schema),
    deleted = 0,
    updated_at = CURRENT_TIMESTAMP;
