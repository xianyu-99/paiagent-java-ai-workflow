package com.paiagent.config;

import com.paiagent.service.TextEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class KnowledgeBaseMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final TextEmbeddingService textEmbeddingService;

    private static final String DEFAULT_LLM_OUTPUT_SCHEMA = """
            {"type":"object","properties":{"output":{"type":"object","properties":{"answer":{"type":"string"},"citations":{"type":"array","items":{"type":"string"}},"confidence":{"type":"number"},"resolved":{"type":"boolean"},"nextAction":{"type":"string"},"ticketSummary":{"type":"string"},"escalationReason":{"type":"string"}}},"answer":{"type":"string"},"citations":{"type":"array","items":{"type":"string"}},"confidence":{"type":"number"},"resolved":{"type":"boolean"},"nextAction":{"type":"string"},"ticketSummary":{"type":"string"},"escalationReason":{"type":"string"},"tokens":{"type":"number"},"inputTokens":{"type":"number"},"outputTokens":{"type":"number"},"totalTokens":{"type":"number"}}}
            """;

    private static final String DEFAULT_LLM_CONFIG_SCHEMA = """
            {"type":"object","properties":{"provider":{"type":"string"},"configId":{"type":"number"},"apiKey":{"type":"string"},"model":{"type":"string"},"skillName":{"type":"string","default":"service-desk-answer"},"prompt":{"type":"string","default":"你是企业服务台助手。请结合用户问题、RAG 上下文和引用来源回答，只输出 answer、citations、confidence、resolved、nextAction、ticketSummary、escalationReason 组成的 JSON。"},"temperature":{"type":"number","default":0.2},"maxTokens":{"type":"number","default":1200}}}
            """;

    public KnowledgeBaseMigrationRunner(JdbcTemplate jdbcTemplate,
                                        TextEmbeddingService textEmbeddingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.textEmbeddingService = textEmbeddingService;
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
        upsertAgentNodeDefinition();
        upsertMediaNodeDefinition();
        upsertQueryEnhancementNodeDefinitions();
        seedEnterpriseServiceDeskKnowledge();
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
                    INDEX idx_chunk_doc_id (document_id),
                    FULLTEXT INDEX idx_chunk_fulltext (content, source_name, section_title)
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
        addIndexIfMissing("knowledge_chunk", "idx_chunk_fulltext",
                "ALTER TABLE knowledge_chunk ADD FULLTEXT INDEX idx_chunk_fulltext (content, source_name, section_title)");
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
                    '{"type":"object","properties":{"output":{"type":"string"},"context":{"type":"string"},"citations":{"type":"array"},"retrievedChunks":{"type":"array"},"retrievedCount":{"type":"number"}}}',
                    '{"type":"object","properties":{"knowledgeBaseId":{"type":"number"},"retrievalOnly":{"type":"boolean","default":false},"topK":{"type":"number","default":3},"minScore":{"type":"number","default":0},"contextWindow":{"type":"number","default":1},"contextMaxChars":{"type":"number","default":1800},"configId":{"type":"number"},"prompt":{"type":"string"}}}'
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

    private void upsertAgentNodeDefinition() {
        upsertNodeDefinition(
                "agent",
                "智能体",
                "AGENT",
                "🕵️",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"output\":{\"type\":\"string\"},\"thoughts\":{\"type\":\"array\"},\"iterations\":{\"type\":\"number\"}}}",
                "{\"type\":\"object\",\"properties\":{\"provider\":{\"type\":\"string\"},\"configId\":{\"type\":\"number\"},\"model\":{\"type\":\"string\"},\"systemPrompt\":{\"type\":\"string\",\"default\":\"你是一个智能助手，可以使用工具帮助用户解决问题。\"},\"taskTemplate\":{\"type\":\"string\",\"default\":\"{{input}}\"},\"temperature\":{\"type\":\"number\",\"default\":0.2},\"maxIterations\":{\"type\":\"number\",\"default\":5},\"reasoningMode\":{\"type\":\"string\",\"default\":\"react\"},\"tools\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"enableExecutionMemory\":{\"type\":\"boolean\",\"default\":false}}}"
        );
    }

    private void upsertMediaNodeDefinition() {
        upsertNodeDefinition(
                "media",
                "媒体生成",
                "TOOL",
                "🎬",
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"mediaUrl\":{\"type\":\"string\"},\"mediaType\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"provider\":{\"type\":\"string\",\"default\":\"openai\"},\"apiUrl\":{\"type\":\"string\"},\"apiKey\":{\"type\":\"string\"},\"model\":{\"type\":\"string\",\"default\":\"dall-e-3\"},\"resolution\":{\"type\":\"string\",\"default\":\"1024x1024\"},\"mediaType\":{\"type\":\"string\",\"default\":\"image\"}}}"
        );
    }

    private void upsertQueryEnhancementNodeDefinitions() {
        upsertNodeDefinition(
                "hyde",
                "HyDE 查询改写",
                "KNOWLEDGE",
                "🧭",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"originalQuery\":{\"type\":\"string\"},\"hydeQuery\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"}}}",
                DEFAULT_LLM_CONFIG_SCHEMA.strip()
        );
        upsertNodeDefinition(
                "query_expansion",
                "查询扩展",
                "KNOWLEDGE",
                "🔎",
                "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"originalQuery\":{\"type\":\"string\"},\"expandedQueries\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"output\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"provider\":{\"type\":\"string\"},\"configId\":{\"type\":\"number\"},\"apiKey\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"temperature\":{\"type\":\"number\",\"default\":0.2},\"expansionCount\":{\"type\":\"number\",\"default\":3}}}"
        );
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
                DEFAULT_LLM_OUTPUT_SCHEMA.strip(),
                DEFAULT_LLM_CONFIG_SCHEMA.strip()
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

    private void seedEnterpriseServiceDeskKnowledge() {
        Long knowledgeBaseId = ensureKnowledgeBase(
                "企业服务台示例知识库",
                "企业服务台助手默认示例资料，覆盖 VPN、报销、请假、产品 FAQ 与客服升级规则。"
        );

        seedKnowledgeDocument(
                knowledgeBaseId,
                "VPN 排障 SOP.md",
                "enterprise-vpn-sop",
                "VPN 排障 SOP",
                readSeedResource("knowledge-base/enterprise-service-desk/vpn-troubleshooting-sop.md")
        );
        seedKnowledgeDocument(
                knowledgeBaseId,
                "报销制度.md",
                "enterprise-expense-policy",
                "报销制度",
                readSeedResource("knowledge-base/enterprise-service-desk/expense-policy.md")
        );
        seedKnowledgeDocument(
                knowledgeBaseId,
                "请假制度.md",
                "enterprise-leave-policy",
                "请假制度",
                readSeedResource("knowledge-base/enterprise-service-desk/leave-policy.md")
        );
        seedKnowledgeDocument(
                knowledgeBaseId,
                "产品 FAQ.md",
                "enterprise-product-faq",
                "产品 FAQ",
                readSeedResource("knowledge-base/enterprise-service-desk/product-faq.md")
        );
        seedKnowledgeDocument(
                knowledgeBaseId,
                "客服升级规则.md",
                "enterprise-support-escalation",
                "客服升级规则",
                readSeedResource("knowledge-base/enterprise-service-desk/support-escalation-rules.md")
        );
        seedEnterpriseServiceDeskWorkflow(knowledgeBaseId);
    }

    private void seedEnterpriseServiceDeskWorkflow(Long knowledgeBaseId) {
        String flowData = """
                {
                  "nodes": [
                    {
                      "id": "input-default",
                      "type": "input",
                      "position": {"x": 80, "y": 180},
                      "data": {"label": "Input 用户问题", "type": "input"}
                    },
                    {
                      "id": "rag-enterprise-kb",
                      "type": "rag",
                      "position": {"x": 330, "y": 180},
                      "data": {
                        "label": "RAG 检索企业知识库",
                        "type": "rag",
                        "knowledgeBaseId": %d,
                        "retrievalOnly": true,
                        "topK": 4,
                        "minScore": 0,
                        "contextWindow": 1,
                        "contextMaxChars": 2400,
                        "inputParams": [
                          {"name": "question", "type": "reference", "referenceNode": "input-default.input"}
                        ]
                      }
                    },
                    {
                      "id": "llm-service-desk",
                      "type": "llm",
                      "position": {"x": 610, "y": 180},
                      "data": {
                        "label": "LLM 生成带引用答案",
                        "type": "llm",
                        "skillName": "service-desk-answer",
                        "temperature": 0.2,
                        "prompt": "请基于用户问题、RAG 上下文和引用来源生成企业服务台处理结果。用户问题：{{question}}。RAG 上下文：{{context}}。引用来源：{{citations}}。只输出固定 JSON 字段：answer、citations、confidence、resolved、nextAction、ticketSummary、escalationReason。",
                        "inputParams": [
                          {"name": "question", "type": "reference", "referenceNode": "input-default.input"},
                          {"name": "context", "type": "reference", "referenceNode": "rag-enterprise-kb.context"},
                          {"name": "citations", "type": "reference", "referenceNode": "rag-enterprise-kb.citations"}
                        ],
                        "outputParams": [
                          {"name": "output", "type": "object", "description": "企业服务台结构化 JSON"}
                        ]
                      }
                    },
                    {
                      "id": "condition-high-confidence",
                      "type": "condition",
                      "position": {"x": 900, "y": 260},
                      "data": {
                        "label": "置信度 >= 0.80",
                        "type": "condition",
                        "leftType": "reference",
                        "leftReference": "llm-service-desk.confidence",
                        "operator": "gte",
                        "rightValue": "0.8",
                        "caseSensitive": false
                      }
                    },
                    {
                      "id": "condition-direct-action",
                      "type": "condition",
                      "position": {"x": 1180, "y": 80},
                      "data": {
                        "label": "动作：直接答复",
                        "type": "condition",
                        "leftType": "reference",
                        "leftReference": "llm-service-desk.nextAction",
                        "operator": "equals",
                        "rightValue": "direct_answer",
                        "caseSensitive": false
                      }
                    },
                    {
                      "id": "condition-ticket-action",
                      "type": "condition",
                      "position": {"x": 1180, "y": 440},
                      "data": {
                        "label": "动作：生成工单",
                        "type": "condition",
                        "leftType": "reference",
                        "leftReference": "llm-service-desk.nextAction",
                        "operator": "equals",
                        "rightValue": "create_ticket",
                        "caseSensitive": false
                      }
                    },
                    {
                      "id": "output-direct-answer",
                      "type": "output",
                      "position": {"x": 1470, "y": 80},
                      "data": {
                        "label": "Output 直接回答",
                        "type": "output",
                        "outputParams": [
                          {"name": "answerPayload", "type": "reference", "referenceNode": "llm-service-desk.output"}
                        ],
                        "responseContent": "{{answerPayload}}"
                      }
                    },
                    {
                      "id": "output-create-ticket",
                      "type": "output",
                      "position": {"x": 1470, "y": 350},
                      "data": {
                        "label": "Output 工单摘要",
                        "type": "output",
                        "outputParams": [
                          {"name": "answerPayload", "type": "reference", "referenceNode": "llm-service-desk.output"}
                        ],
                        "responseContent": "{{answerPayload}}"
                      }
                    },
                    {
                      "id": "output-escalate-human",
                      "type": "output",
                      "position": {"x": 1470, "y": 560},
                      "data": {
                        "label": "Output 升级人工",
                        "type": "output",
                        "outputParams": [
                          {"name": "answerPayload", "type": "reference", "referenceNode": "llm-service-desk.output"}
                        ],
                        "responseContent": "{{answerPayload}}"
                      }
                    }
                  ],
                  "edges": [
                    {"id": "edge-input-rag", "source": "input-default", "target": "rag-enterprise-kb"},
                    {"id": "edge-rag-llm", "source": "rag-enterprise-kb", "target": "llm-service-desk"},
                    {"id": "edge-llm-confidence", "source": "llm-service-desk", "target": "condition-high-confidence"},
                    {"id": "edge-confidence-direct-action", "source": "condition-high-confidence", "target": "condition-direct-action", "sourceHandle": "true"},
                    {"id": "edge-confidence-ticket-action", "source": "condition-high-confidence", "target": "condition-ticket-action", "sourceHandle": "false"},
                    {"id": "edge-direct-action-answer", "source": "condition-direct-action", "target": "output-direct-answer", "sourceHandle": "true"},
                    {"id": "edge-direct-action-ticket", "source": "condition-direct-action", "target": "condition-ticket-action", "sourceHandle": "false"},
                    {"id": "edge-ticket-action-ticket", "source": "condition-ticket-action", "target": "output-create-ticket", "sourceHandle": "true"},
                    {"id": "edge-ticket-action-escalate", "source": "condition-ticket-action", "target": "output-escalate-human", "sourceHandle": "false"}
                  ]
                }
                """.formatted(knowledgeBaseId);

        String workflowDescription = "默认企业内部服务台 / 知识流程助手 Demo：Input -> RAG -> LLM -> 置信度 / nextAction 路由 -> Output。";
        Long existingWorkflowId = jdbcTemplate.query("""
                        SELECT id FROM workflow
                        WHERE name = ? AND owner_id IS NULL AND deleted = 0
                        ORDER BY id ASC
                        LIMIT 1
                        """,
                rs -> rs.next() ? rs.getLong("id") : null,
                "企业服务台助手"
        );
        if (existingWorkflowId != null) {
            jdbcTemplate.update("""
                            UPDATE workflow
                            SET description = ?, flow_data = ?, engine_type = 'dag'
                            WHERE id = ?
                            """,
                    workflowDescription,
                    flowData,
                    existingWorkflowId
            );
            return;
        }

        jdbcTemplate.update("""
                        INSERT INTO workflow (name, description, flow_data, engine_type, owner_id, deleted)
                        VALUES (?, ?, ?, 'dag', NULL, 0)
                        """,
                "企业服务台助手",
                workflowDescription,
                flowData
        );
    }

    private Long ensureKnowledgeBase(String name, String description) {
        Long existingId = jdbcTemplate.query("""
                        SELECT id FROM knowledge_base
                        WHERE name = ? AND deleted = 0
                        ORDER BY id ASC
                        LIMIT 1
                        """,
                rs -> rs.next() ? rs.getLong("id") : null,
                name
        );
        if (existingId != null) {
            return existingId;
        }

        jdbcTemplate.update(
                "INSERT INTO knowledge_base (name, description, owner_id, deleted) VALUES (?, ?, NULL, 0)",
                name,
                description
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String readSeedResource(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read service desk seed resource: " + classpathLocation, e);
        }
    }

    private void seedKnowledgeDocument(Long knowledgeBaseId,
                                       String fileName,
                                       String contentHash,
                                       String sectionTitle,
                                       String content) {
        String normalizedContent = content.strip();
        int tokenCount = Math.max(1, normalizedContent.length() / 4);

        Long existingDocumentId = jdbcTemplate.query("""
                        SELECT id FROM knowledge_document
                        WHERE knowledge_base_id = ? AND content_hash = ? AND deleted = 0
                        ORDER BY id ASC
                        LIMIT 1
                        """,
                rs -> rs.next() ? rs.getLong("id") : null,
                knowledgeBaseId,
                contentHash
        );

        Long documentId = existingDocumentId;
        if (documentId == null) {
            jdbcTemplate.update("""
                            INSERT INTO knowledge_document
                                (knowledge_base_id, owner_id, file_name, content_type, parser_type, content_hash, chunk_count, deleted)
                            VALUES (?, NULL, ?, 'text/markdown', 'seed', ?, 1, 0)
                            """,
                    knowledgeBaseId,
                    fileName,
                    contentHash
            );
            documentId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            jdbcTemplate.update("""
                            UPDATE knowledge_document
                            SET file_name = ?, content_type = 'text/markdown', parser_type = 'seed', chunk_count = 1
                            WHERE id = ?
                            """,
                    fileName,
                    documentId
            );
        }

        if (existingDocumentId != null) {
            Integer existingChunkCount = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*) FROM knowledge_chunk
                            WHERE knowledge_base_id = ? AND document_id = ? AND chunk_index = 0 AND deleted = 0
                            """,
                    Integer.class,
                    knowledgeBaseId,
                    documentId
            );
            if (existingChunkCount != null && existingChunkCount > 0) {
                jdbcTemplate.update("""
                                UPDATE knowledge_chunk
                                SET content = ?,
                                    source_name = ?,
                                    content_type = 'text/markdown',
                                    section_title = ?,
                                    start_offset = 0,
                                    end_offset = ?,
                                    token_count = ?
                                WHERE knowledge_base_id = ?
                                  AND document_id = ?
                                  AND chunk_index = 0
                                  AND deleted = 0
                                """,
                        normalizedContent,
                        fileName,
                        sectionTitle,
                        normalizedContent.length(),
                        tokenCount,
                        knowledgeBaseId,
                        documentId
                );
                return;
            }
        }

        List<Double> embedding = textEmbeddingService.embed(normalizedContent);
        String embeddingJson = textEmbeddingService.serialize(embedding);

        Integer chunkCount = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM knowledge_chunk
                        WHERE knowledge_base_id = ? AND document_id = ? AND source_name = ? AND chunk_index = 0 AND deleted = 0
                        """,
                Integer.class,
                knowledgeBaseId,
                documentId,
                fileName
        );
        if (chunkCount != null && chunkCount > 0) {
            jdbcTemplate.update("""
                            UPDATE knowledge_chunk
                            SET content = ?,
                                content_type = 'text/markdown',
                                section_title = ?,
                                start_offset = 0,
                                end_offset = ?,
                                embedding = ?,
                                embedding_provider = ?,
                                embedding_model = ?,
                                embedding_dimension = ?,
                                token_count = ?
                            WHERE knowledge_base_id = ?
                              AND document_id = ?
                              AND source_name = ?
                              AND chunk_index = 0
                              AND deleted = 0
                            """,
                    normalizedContent,
                    sectionTitle,
                    normalizedContent.length(),
                    embeddingJson,
                    textEmbeddingService.provider(),
                    textEmbeddingService.model(),
                    textEmbeddingService.dimensions(),
                    tokenCount,
                    knowledgeBaseId,
                    documentId,
                    fileName
            );
            return;
        }

        jdbcTemplate.update("""
                        INSERT INTO knowledge_chunk
                            (knowledge_base_id, document_id, chunk_index, content, source_name, content_type, section_title, page_number,
                             start_offset, end_offset, embedding, embedding_provider, embedding_model, embedding_dimension, token_count, deleted)
                        VALUES (?, ?, 0, ?, ?, 'text/markdown', ?, NULL, 0, ?, ?, ?, ?, ?, ?, 0)
                        """,
                knowledgeBaseId,
                documentId,
                normalizedContent,
                fileName,
                sectionTitle,
                normalizedContent.length(),
                embeddingJson,
                textEmbeddingService.provider(),
                textEmbeddingService.model(),
                textEmbeddingService.dimensions(),
                tokenCount
        );
    }

}
