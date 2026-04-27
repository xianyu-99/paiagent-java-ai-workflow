package com.paiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KnowledgeBaseMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        createKnowledgeBaseTable();
        createKnowledgeDocumentTable();
        createKnowledgeChunkTable();
        upsertRagNodeDefinition();
    }

    private void createKnowledgeBaseTable() {
        jdbcTemplate.execute("""
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库表'
                """);
    }

    private void createKnowledgeDocumentTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_document (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    knowledge_base_id BIGINT NOT NULL,
                    owner_id BIGINT NULL,
                    file_name VARCHAR(255) NOT NULL,
                    content_hash VARCHAR(64) NOT NULL,
                    chunk_count INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    INDEX idx_doc_kb_id (knowledge_base_id),
                    INDEX idx_doc_owner_id (owner_id),
                    INDEX idx_doc_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库文档表'
                """);
    }

    private void createKnowledgeChunkTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_chunk (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    knowledge_base_id BIGINT NOT NULL,
                    document_id BIGINT NOT NULL,
                    chunk_index INT NOT NULL,
                    content MEDIUMTEXT NOT NULL,
                    embedding JSON NOT NULL,
                    token_count INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    INDEX idx_chunk_kb_id (knowledge_base_id),
                    INDEX idx_chunk_doc_id (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库切片表'
                """);
    }

    private void upsertRagNodeDefinition() {
        jdbcTemplate.update("""
                INSERT INTO node_definition (node_type, display_name, category, icon, input_schema, output_schema, config_schema)
                VALUES (
                    'rag',
                    '知识库问答',
                    'KNOWLEDGE',
                    '📚',
                    '{"type":"object","properties":{"question":{"type":"string"}}}',
                    '{"type":"object","properties":{"output":{"type":"string"},"context":{"type":"string"},"retrievedChunks":{"type":"array"}}}',
                    '{"type":"object","properties":{"knowledgeBaseId":{"type":"number"},"topK":{"type":"number","default":3},"minScore":{"type":"number","default":0},"configId":{"type":"number"},"prompt":{"type":"string"}}}'
                )
                ON DUPLICATE KEY UPDATE
                    display_name = VALUES(display_name),
                    category = VALUES(category),
                    icon = VALUES(icon),
                    input_schema = VALUES(input_schema),
                    output_schema = VALUES(output_schema),
                    config_schema = VALUES(config_schema),
                    updated_at = CURRENT_TIMESTAMP
                """);
        log.info("RAG 知识库表与节点定义迁移完成");
    }
}
