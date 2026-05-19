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
        migrateKnowledgeDocumentMetadata();
        createKnowledgeChunkTable();
        migrateKnowledgeChunkEmbeddingMetadata();
        migrateKnowledgeChunkSourceMetadata();
        createKnowledgeImportTaskTable();
        upsertCoreNodeDefinitions();
        hideLegacyProviderNodeDefinitions();
        upsertTtsNodeDefinition();
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库文档表'
                """);
    }

    private void migrateKnowledgeDocumentMetadata() {
        addColumnIfMissing("knowledge_document", "content_type",
                "ALTER TABLE knowledge_document ADD COLUMN content_type VARCHAR(150) NULL AFTER file_name");
        addColumnIfMissing("knowledge_document", "parser_type",
                "ALTER TABLE knowledge_document ADD COLUMN parser_type VARCHAR(50) NULL AFTER content_type");
    }

    private void createKnowledgeChunkTable() {
        jdbcTemplate.execute("""
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
                    token_count INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    deleted TINYINT DEFAULT 0,
                    INDEX idx_chunk_kb_id (knowledge_base_id),
                    INDEX idx_chunk_doc_id (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库切片表'
                """);
    }

    private void migrateKnowledgeChunkEmbeddingMetadata() {
        addColumnIfMissing("knowledge_chunk", "embedding_provider",
                "ALTER TABLE knowledge_chunk ADD COLUMN embedding_provider VARCHAR(50) NULL AFTER embedding");
        addColumnIfMissing("knowledge_chunk", "embedding_model",
                "ALTER TABLE knowledge_chunk ADD COLUMN embedding_model VARCHAR(100) NULL AFTER embedding_provider");
        addColumnIfMissing("knowledge_chunk", "embedding_dimension",
                "ALTER TABLE knowledge_chunk ADD COLUMN embedding_dimension INT NULL AFTER embedding_model");
        addIndexIfMissing("knowledge_chunk", "idx_chunk_embedding_meta",
                "ALTER TABLE knowledge_chunk ADD INDEX idx_chunk_embedding_meta (knowledge_base_id, embedding_provider, embedding_model, embedding_dimension)");
    }

    private void migrateKnowledgeChunkSourceMetadata() {
        addColumnIfMissing("knowledge_chunk", "source_name",
                "ALTER TABLE knowledge_chunk ADD COLUMN source_name VARCHAR(255) NULL AFTER content");
        addColumnIfMissing("knowledge_chunk", "content_type",
                "ALTER TABLE knowledge_chunk ADD COLUMN content_type VARCHAR(150) NULL AFTER source_name");
        addColumnIfMissing("knowledge_chunk", "section_title",
                "ALTER TABLE knowledge_chunk ADD COLUMN section_title VARCHAR(500) NULL AFTER content_type");
        addColumnIfMissing("knowledge_chunk", "page_number",
                "ALTER TABLE knowledge_chunk ADD COLUMN page_number INT NULL AFTER section_title");
        addColumnIfMissing("knowledge_chunk", "start_offset",
                "ALTER TABLE knowledge_chunk ADD COLUMN start_offset INT NULL AFTER page_number");
        addColumnIfMissing("knowledge_chunk", "end_offset",
                "ALTER TABLE knowledge_chunk ADD COLUMN end_offset INT NULL AFTER start_offset");
        addIndexIfMissing("knowledge_chunk", "idx_chunk_doc_page",
                "ALTER TABLE knowledge_chunk ADD INDEX idx_chunk_doc_page (document_id, page_number, chunk_index)");
    }

    private void createKnowledgeImportTaskTable() {
        jdbcTemplate.execute("""
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识导入任务表'
                """);
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }

    private void addIndexIfMissing(String tableName, String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, tableName, indexName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
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
                    '{"type":"object","properties":{"output":{"type":"string"},"context":{"type":"string"},"retrievedChunks":{"type":"array"},"retrievedCount":{"type":"number"}}}',
                    '{"type":"object","properties":{"knowledgeBaseId":{"type":"number"},"topK":{"type":"number","default":3},"minScore":{"type":"number","default":0},"contextWindow":{"type":"number","default":1},"contextMaxChars":{"type":"number","default":1800},"configId":{"type":"number"},"prompt":{"type":"string"}}}'
                )
                ON DUPLICATE KEY UPDATE
                    display_name = VALUES(display_name),
                    category = VALUES(category),
                    icon = VALUES(icon),
                    input_schema = VALUES(input_schema),
                    output_schema = VALUES(output_schema),
                    config_schema = VALUES(config_schema),
                    deleted = 0,
                    updated_at = CURRENT_TIMESTAMP
                """);
        log.info("RAG 知识库表与节点定义迁移完成");
    }

    private void upsertCoreNodeDefinitions() {
        upsertNodeDefinition(
                "input",
                "输入",
                "IO",
                "📥",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"defaultValue\":{\"type\":\"string\"}}}"
        );
        upsertNodeDefinition(
                "output",
                "输出",
                "IO",
                "📤",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"output\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{}}"
        );
        upsertNodeDefinition(
                "llm",
                "大模型",
                "LLM",
                "🤖",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"output\":{\"type\":\"string\"},\"tokens\":{\"type\":\"number\"}}}",
                "{\"type\":\"object\",\"properties\":{\"provider\":{\"type\":\"string\"},\"configId\":{\"type\":\"number\"},\"apiKey\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"prompt\":{\"type\":\"string\"},\"temperature\":{\"type\":\"number\",\"default\":0.7},\"maxTokens\":{\"type\":\"number\",\"default\":1000}}}"
        );
        upsertNodeDefinition(
                "condition",
                "条件分支",
                "FLOW",
                "🔀",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"conditionResult\":{\"type\":\"boolean\"},\"selectedBranch\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"leftType\":{\"type\":\"string\",\"default\":\"reference\"},\"leftReference\":{\"type\":\"string\"},\"leftValue\":{\"type\":\"string\"},\"operator\":{\"type\":\"string\",\"default\":\"equals\"},\"rightValue\":{\"type\":\"string\"},\"caseSensitive\":{\"type\":\"boolean\",\"default\":false}}}"
        );
    }

    private void hideLegacyProviderNodeDefinitions() {
        jdbcTemplate.update("""
                UPDATE node_definition
                SET deleted = 1, updated_at = CURRENT_TIMESTAMP
                WHERE node_type IN ('openai', 'deepseek', 'qwen', 'zhipu', 'step', 'ai_ping')
                """);
    }

    private void upsertNodeDefinition(String nodeType,
                                      String displayName,
                                      String category,
                                      String icon,
                                      String inputSchema,
                                      String outputSchema,
                                      String configSchema) {
        jdbcTemplate.update("""
                INSERT INTO node_definition (node_type, display_name, category, icon, input_schema, output_schema, config_schema)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    display_name = VALUES(display_name),
                    category = VALUES(category),
                    icon = VALUES(icon),
                    input_schema = VALUES(input_schema),
                    output_schema = VALUES(output_schema),
                    config_schema = VALUES(config_schema),
                    deleted = 0,
                    updated_at = CURRENT_TIMESTAMP
                """, nodeType, displayName, category, icon, inputSchema, outputSchema, configSchema);
    }

    private void upsertTtsNodeDefinition() {
        jdbcTemplate.update("""
                INSERT INTO node_definition (node_type, display_name, category, icon, input_schema, output_schema, config_schema)
                VALUES (
                    'tts',
                    '超拟人音频合成',
                    'TOOL',
                    '🔊',
                    '{"type":"object","properties":{"text":{"type":"string"}}}',
                    '{"type":"object","properties":{"audioUrl":{"type":"string"},"fileName":{"type":"string"},"output":{"type":"string"},"chunks":{"type":"number"}}}',
                    '{"type":"object","properties":{"provider":{"type":"string","default":"qwen"},"apiUrl":{"type":"string"},"apiKey":{"type":"string"},"model":{"type":"string","default":"qwen3-tts-flash"},"voice":{"type":"string","default":"Cherry"},"style":{"type":"string"},"languageType":{"type":"string","default":"Auto"}}}'
                )
                ON DUPLICATE KEY UPDATE
                    display_name = VALUES(display_name),
                    category = VALUES(category),
                    icon = VALUES(icon),
                    input_schema = VALUES(input_schema),
                    output_schema = VALUES(output_schema),
                    config_schema = VALUES(config_schema),
                    deleted = 0,
                    updated_at = CURRENT_TIMESTAMP
                """);
    }

}
