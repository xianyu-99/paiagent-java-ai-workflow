-- 创建数据库
CREATE DATABASE IF NOT EXISTS paiagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS paiagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE paiagent;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- app user table
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='app_user';

-- 工作流表
CREATE TABLE IF NOT EXISTS workflow (
    owner_id BIGINT NULL,
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '工作流主键 ID',
    name VARCHAR(255) NOT NULL COMMENT '工作流名称',
    description TEXT COMMENT '工作流描述',
    flow_data JSON NOT NULL COMMENT '工作流配置数据(节点和连线)',
    engine_type VARCHAR(50) DEFAULT 'dag' COMMENT '工作流引擎类型(dag/langgraph)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    INDEX idx_owner_id (owner_id),
    INDEX idx_created_at (created_at),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流表';

CREATE TABLE IF NOT EXISTS workflow_publish (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '发布记录主键 ID',
    workflow_id BIGINT NOT NULL COMMENT '工作流 ID',
    share_key VARCHAR(64) NOT NULL COMMENT '公开页面标识',
    api_access_key VARCHAR(512) NULL COMMENT 'API 调用访问密钥',
    title VARCHAR(255) NOT NULL COMMENT '公开标题',
    description TEXT COMMENT '公开描述',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_by BIGINT NULL COMMENT '发布人用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    UNIQUE KEY uk_publish_share_key (share_key),
    UNIQUE KEY uk_publish_workflow_id (workflow_id),
    INDEX idx_publish_enabled (enabled),
    INDEX idx_publish_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流发布表';

-- 节点定义表
CREATE TABLE IF NOT EXISTS node_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '节点定义主键 ID',
    node_type VARCHAR(100) NOT NULL UNIQUE COMMENT '节点类型标识',
    display_name VARCHAR(255) NOT NULL COMMENT '显示名称',
    category VARCHAR(50) NOT NULL COMMENT '节点分类(LLM/TOOL)',
    icon VARCHAR(255) COMMENT '节点图标',
    input_schema JSON COMMENT '输入参数 JSON Schema',
    output_schema JSON COMMENT '输出参数 JSON Schema',
    config_schema JSON COMMENT '配置参数 JSON Schema',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节点定义表';

-- 执行记录表
CREATE TABLE IF NOT EXISTS execution_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '执行记录主键 ID',
    flow_id BIGINT NOT NULL COMMENT '工作流 ID',
    input_data JSON COMMENT '输入数据',
    output_data JSON COMMENT '输出数据',
    status VARCHAR(50) NOT NULL COMMENT '执行状态(SUCCESS/FAILED)',
    node_results JSON COMMENT '每个节点的执行结果',
    error_message TEXT COMMENT '错误信息',
    error_log JSON COMMENT '结构化错误日志',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    timeout_count INT DEFAULT 0 COMMENT '超时次数',
    duration INT COMMENT '执行耗时(毫秒)',
    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    INDEX idx_flow_id (flow_id),
    INDEX idx_executed_at (executed_at),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行记录表';

-- RAG 知识库表
CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识库主键 ID',
    name VARCHAR(255) NOT NULL COMMENT '知识库名称',
    description TEXT COMMENT '知识库描述',
    owner_id BIGINT NULL COMMENT '知识库所有者用户 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    INDEX idx_kb_owner_id (owner_id),
    INDEX idx_kb_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库表';

-- RAG 文档表
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '文档主键 ID',
    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
    owner_id BIGINT NULL COMMENT '上传用户 ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    content_type VARCHAR(150) NULL COMMENT '文档 MIME 类型',
    parser_type VARCHAR(50) NULL COMMENT '解析器类型',
    content_hash VARCHAR(64) NOT NULL COMMENT '文档内容 SHA-256',
    chunk_count INT DEFAULT 0 COMMENT '切片数量',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    INDEX idx_doc_kb_id (knowledge_base_id),
    INDEX idx_doc_owner_id (owner_id),
    INDEX idx_doc_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库文档表';

-- RAG 文本切片与向量表
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '切片主键 ID',
    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
    document_id BIGINT NOT NULL COMMENT '文档 ID',
    chunk_index INT NOT NULL COMMENT '文档内切片序号',
    content MEDIUMTEXT NOT NULL COMMENT '切片文本',
    source_name VARCHAR(255) NULL COMMENT '来源文件名',
    content_type VARCHAR(150) NULL COMMENT '来源 MIME 类型',
    section_title VARCHAR(500) NULL COMMENT '章节标题',
    page_number INT NULL COMMENT 'PDF 页码',
    start_offset INT NULL COMMENT '原文起始偏移',
    end_offset INT NULL COMMENT '原文结束偏移',
    embedding JSON NOT NULL COMMENT '切片向量 JSON',
    embedding_provider VARCHAR(50) NULL COMMENT 'Embedding provider',
    embedding_model VARCHAR(100) NULL COMMENT 'Embedding model',
    embedding_dimension INT NULL COMMENT 'Embedding dimension',
    token_count INT DEFAULT 0 COMMENT '估算 token 数',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    INDEX idx_chunk_kb_id (knowledge_base_id),
    INDEX idx_chunk_doc_id (document_id),
    INDEX idx_chunk_doc_page (document_id, page_number, chunk_index),
    INDEX idx_chunk_embedding_meta (knowledge_base_id, embedding_provider, embedding_model, embedding_dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识库切片表';

-- RAG 知识导入任务表
CREATE TABLE IF NOT EXISTS knowledge_import_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '导入任务主键 ID',
    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
    owner_id BIGINT NULL COMMENT '上传用户 ID',
    document_id BIGINT NULL COMMENT '导入成功后的文档 ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    content_type VARCHAR(150) NULL COMMENT '文档 MIME 类型',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态 PENDING/RUNNING/SUCCESS/FAILED',
    stage VARCHAR(255) NULL COMMENT '当前阶段',
    progress INT DEFAULT 0 COMMENT '进度百分比',
    total_chunks INT DEFAULT 0 COMMENT '总切片数',
    processed_chunks INT DEFAULT 0 COMMENT '已处理切片数',
    error_message TEXT COMMENT '失败原因',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    completed_at TIMESTAMP NULL COMMENT '完成时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    INDEX idx_import_kb_id (knowledge_base_id),
    INDEX idx_import_owner_id (owner_id),
    INDEX idx_import_status (status),
    INDEX idx_import_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识导入任务表';

-- 插入预置节点定义数据
INSERT INTO node_definition (node_type, display_name, category, icon, input_schema, output_schema, config_schema) VALUES
('input', '输入', 'IO', '📥',
 '{"type": "object", "properties": {}}',
 '{"type": "object", "properties": {"input": {"type": "string"}}}',
 '{"type": "object", "properties": {"defaultValue": {"type": "string"}}}'),

('output', '输出', 'IO', '📤',
 '{"type": "object", "properties": {"input": {"type": "string"}}}',
 '{"type": "object", "properties": {"output": {"type": "string"}}}',
 '{"type": "object", "properties": {}}'),

('llm', '大模型', 'LLM', '🤖',
 '{"type": "object", "properties": {"input": {"type": "string"}}}',
 '{"type": "object", "properties": {"output": {"type": "string"}, "tokens": {"type": "number"}}}',
 '{"type": "object", "properties": {"provider": {"type": "string"}, "configId": {"type": "number"}, "apiKey": {"type": "string"}, "model": {"type": "string"}, "prompt": {"type": "string"}, "temperature": {"type": "number", "default": 0.7}, "maxTokens": {"type": "number", "default": 1000}}}'),

('tts', '超拟人音频合成', 'TOOL', '🔊',
 '{"type": "object", "properties": {"text": {"type": "string"}}}',
 '{"type": "object", "properties": {"audioUrl": {"type": "string"}, "fileName": {"type": "string"}, "output": {"type": "string"}, "chunks": {"type": "number"}}}',
 '{"type": "object", "properties": {"apiKey": {"type": "string"}, "model": {"type": "string", "default": "qwen3-tts-flash"}, "voice": {"type": "string", "default": "Cherry"}, "languageType": {"type": "string", "default": "Auto"}}}'),

('condition', '条件分支', 'FLOW', '🔀',
 '{"type": "object", "properties": {"input": {"type": "string"}, "output": {"type": "string"}}}',
 '{"type": "object", "properties": {"conditionResult": {"type": "boolean"}, "selectedBranch": {"type": "string"}, "output": {"type": "string"}}}',
 '{"type": "object", "properties": {"leftType": {"type": "string", "default": "reference"}, "leftReference": {"type": "string"}, "leftValue": {"type": "string"}, "operator": {"type": "string", "default": "equals"}, "rightValue": {"type": "string"}, "caseSensitive": {"type": "boolean", "default": false}}}'),

('rag', '知识库问答', 'KNOWLEDGE', '📚',
 '{"type": "object", "properties": {"question": {"type": "string"}}}',
 '{"type": "object", "properties": {"output": {"type": "string"}, "context": {"type": "string"}, "retrievedChunks": {"type": "array"}, "retrievedCount": {"type": "number"}}}',
 '{"type": "object", "properties": {"knowledgeBaseId": {"type": "number"}, "topK": {"type": "number", "default": 3}, "minScore": {"type": "number", "default": 0}, "contextWindow": {"type": "number", "default": 1}, "contextMaxChars": {"type": "number", "default": 1800}, "configId": {"type": "number"}, "prompt": {"type": "string"}}}')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    category = VALUES(category),
    icon = VALUES(icon),
    input_schema = VALUES(input_schema),
    output_schema = VALUES(output_schema),
    config_schema = VALUES(config_schema),
    deleted = 0,
    updated_at = CURRENT_TIMESTAMP;


-- 全局 LLM 配置表
CREATE TABLE IF NOT EXISTS llm_global_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配置主键 ID',
    provider VARCHAR(50) NOT NULL COMMENT '提供商: openai/deepseek/qwen/step',
    config_name VARCHAR(100) NOT NULL COMMENT '配置名称',
    api_url VARCHAR(255) NOT NULL COMMENT 'API地址',
    api_key TEXT NOT NULL COMMENT 'API密钥',
    model VARCHAR(100) NOT NULL COMMENT '默认模型',
    temperature DECIMAL(3,2) DEFAULT 0.7 COMMENT '默认温度',
    is_default TINYINT DEFAULT 0 COMMENT '是否默认配置(0-否,1-是)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    UNIQUE KEY uk_provider_config_name (provider, config_name),
    INDEX idx_provider (provider),
    INDEX idx_is_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全局LLM配置表';
