# PaiAgent Backend

Spring Boot 后端服务，负责用户认证、工作流编排、节点执行、LLM 统一调用、TTS 音频合成、RAG 知识库和执行记录管理。

## 技术栈

- Java 21
- Spring Boot 3.4.1
- Spring AI OpenAI 兼容接口
- LangGraph4j
- MyBatis-Plus
- MySQL / Redis
- MinIO
- Qdrant 可选向量库

## 本地启动

1. 复制并编辑本地配置：

```powershell
Copy-Item .env.example .env
```

2. 初始化数据库：

```powershell
mysql -u root -p paiagent < src/main/resources/schema.sql
```

3. 启动后端：

```powershell
mvn spring-boot:run
```

默认端口：`8084`

Swagger：`http://localhost:8084/swagger-ui.html`

## 关键环境变量

```properties
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=paiagent
MYSQL_USERNAME=root
MYSQL_PASSWORD=your_password

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=your_redis_password

JWT_SECRET=your_jwt_secret_minimum_32_chars
API_KEY_ENCRYPTION_SECRET=your_api_key_encryption_secret

APP_AUTH_DEFAULT_USERNAME=admin
APP_AUTH_DEFAULT_PASSWORD=admin123
```

默认会拒绝 LLM/TTS 访问内网或本机 URL，以降低 SSRF 风险。需要接入本地模型服务（如 Ollama、LM Studio、内网 OpenAI-compatible 网关）时，可显式设置：

```properties
PAIAGENT_SECURITY_ALLOW_PRIVATE_NETWORK_URLS=true
```

即使开启该选项，云厂商 metadata 地址仍会被拦截。

RAG 默认使用本地 Hash Embedding + MySQL 向量检索，可通过环境变量切换到 DashScope Embedding 和 Qdrant。

## 主要模块

- `engine/`：DAG 与 LangGraph 工作流执行。
- `engine/executor/`：Input、Output、LLM、TTS、Condition、RAG 等节点执行器。
- `service/KnowledgeBaseService.java`：知识库导入、切片、Embedding、混合检索与 Rerank。
- `service/document/`：TXT、Markdown、PDF、DOCX 文档解析。
- `service/vector/`：MySQL / Qdrant 向量存储适配。
- `config/*MigrationRunner.java`：本地开发自动补齐增量表结构。

## 测试

```powershell
mvn test
```
