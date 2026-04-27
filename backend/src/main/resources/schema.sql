-- 创建数据库
CREATE DATABASE IF NOT EXISTS paiagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS paiagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE paiagent;

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
    duration INT COMMENT '执行耗时(毫秒)',
    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识(0-未删除,1-已删除)',
    INDEX idx_flow_id (flow_id),
    INDEX idx_executed_at (executed_at),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行记录表';

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

('openai', 'OpenAI', 'LLM', '🤖', 
 '{"type": "object", "properties": {"input": {"type": "string"}}}',
 '{"type": "object", "properties": {"output": {"type": "string"}, "tokens": {"type": "number"}}}',
 '{"type": "object", "properties": {"apiKey": {"type": "string"}, "model": {"type": "string", "default": "gpt-3.5-turbo"}, "prompt": {"type": "string"}, "temperature": {"type": "number", "default": 0.7}, "maxTokens": {"type": "number", "default": 1000}}}'),
 
('deepseek', 'DeepSeek', 'LLM', '🧠',
 '{"type": "object", "properties": {"input": {"type": "string"}}}',
 '{"type": "object", "properties": {"output": {"type": "string"}, "tokens": {"type": "number"}}}',
 '{"type": "object", "properties": {"apiKey": {"type": "string"}, "model": {"type": "string", "default": "deepseek-chat"}, "prompt": {"type": "string"}, "temperature": {"type": "number", "default": 0.7}, "maxTokens": {"type": "number", "default": 1000}}}'),
 
('qwen', '通义千问', 'LLM', '🌟',
 '{"type": "object", "properties": {"input": {"type": "string"}}}',
 '{"type": "object", "properties": {"output": {"type": "string"}, "tokens": {"type": "number"}}}',
 '{"type": "object", "properties": {"apiKey": {"type": "string"}, "model": {"type": "string", "default": "qwen-turbo"}, "prompt": {"type": "string"}, "temperature": {"type": "number", "default": 0.7}, "maxTokens": {"type": "number", "default": 1000}}}'),

('step', 'Step', 'LLM', '🟆',
 '{"type": "object", "properties": {"input": {"type": "string"}}}',
 '{"type": "object", "properties": {"output": {"type": "string"}, "tokens": {"type": "number"}}}',
 '{"type": "object", "properties": {"apiKey": {"type": "string"}, "model": {"type": "string", "default": "claude-3-5-sonnet-20241022"}, "prompt": {"type": "string"}, "temperature": {"type": "number", "default": 0.7}, "maxTokens": {"type": "number", "default": 1000}}}'),
 
('tts', '超拟人音频合成', 'TOOL', '🔊',
 '{"type": "object", "properties": {"text": {"type": "string"}}}',
 '{"type": "object", "properties": {"audioUrl": {"type": "string"}, "duration": {"type": "number"}, "fileSize": {"type": "number"}}}',
 '{"type": "object", "properties": {"apiKey": {"type": "string"}, "voice": {"type": "string", "default": "female"}, "speed": {"type": "number", "default": 1.0}, "volume": {"type": "number", "default": 80}}}');

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
