# ServerAgent Java AI Workflow

基于 Java 21、Spring Boot、ReactFlow 的企业内部服务台 / 知识流程助手平台。项目支持通过拖拽方式组合 `Input -> RAG -> LLM -> Condition -> Output` 等节点，让 IT、HR、行政、客服等场景可以基于企业知识库生成带引用答案，并按置信度自动决定直接回答、创建工单或升级人工。

本仓库基于 [itwanger/PaiAgent](https://github.com/itwanger/PaiAgent) 进行本地复现与二次增强，保留原项目的可视化工作流核心思路，并补充了用户体系、敏感配置加密、Docker Compose 部署等工程化能力。当前项目主线收口为企业内部服务台 / 知识流程助手，TTS、ai-podcast 等语音内容能力只作为可选增强，不作为核心演示链路。

## 项目定位

PaiAgent 是一个**基于 Spring Boot 3 + Java 21 虚拟线程 + ReactFlow 的 AI 工作流（Agent Workflow）编排与 RAG 知识库双引擎执行平台**。它填补了 Java 生态中轻量级可视化大模型应用开发框架的空白，为 Java 开发者提供了一套**开箱即用、性能优异、安全合规的大模型工程化落地范例**。

### 🎯 核心场景

主线场景是企业内部员工提问：
```text
用户提问 -> RAG 检索企业知识库 -> LLM 生成结构化答案 -> Condition 节点条件判断是否解决 -> Output 节点输出（直接回答 / 工单摘要 / 升级人工）
```
本地演示在没有真实大模型 Key 时，默认使用内置结构化 Demo Fallback 链路，极易进行离线调试与功能验收。

### 🚀 适合用于

- **Java 大模型应用开发与面试加分展示**：项目深度整合 Spring AI，提供了完整的企业级 Agent 工程实践。
- **Java 虚拟线程高并发实践**：全链路适配 Java 21 Virtual Threads，优化长连接 I/O 阻塞场景下的并发性能。
- **AI 编排引擎与状态图演练**：包含 DAG 执行器（拓扑排序与循环检测）和 LangGraph4j 状态图执行器双引擎。
- **RAG 向量检索与性能调优**：支持 MySQL 本地 JSON 向量与 Qdrant 专用向量库，针对相似度查询做字段投影与内存调优，演示海量切片下的内存控制。

## 核心功能

### 可视化工作流编辑

- 基于 ReactFlow 实现节点拖拽、连线、保存、加载和删除
- 支持输入节点、LLM 节点、TTS 节点、条件分支节点、输出节点
- 支持工作流保存到 MySQL，并在前端重新加载编辑
- 修复节点拖拽落点偏移和重复加载异常问题

![工作流编辑](image/README-29e9a00fc44f42298da9bd230bb8fe96.png)

### DAG 工作流引擎

- 后端基于 DAG 解析工作流节点和边
- 使用拓扑排序确定节点执行顺序
- 使用循环检测避免工作流死循环
- 节点输出会作为下游节点输入继续传递
- 条件节点支持根据 true / false 出口动态选择后续执行分支
- 执行结果会记录到数据库，方便回溯调试

### 条件分支节点

- 支持 if/else 类型的流程控制节点
- 支持引用上游节点输出或填写固定值作为判断左值
- 支持等于、不等于、包含、不包含、为空、大于、小于等常用判断条件
- 条件成立时执行 true 出口，条件不成立时执行 false 出口
- DAG 引擎会跳过未命中分支，LangGraph 引擎会通过 conditional edge 路由到命中分支

### LangGraph4j 状态图引擎

- 支持将可视化工作流转换为 LangGraph4j `StateGraph`
- 支持 `condition` 节点通过 conditional edge 执行 true / false 分支
- 支持循环边，适合表达“执行节点 -> 判断条件 -> 未满足则回到前置节点”的状态流
- 内置最大迭代次数保护，避免错误工作流导致无限循环

### LLM 多模型接入

- 支持 OpenAI 兼容格式的模型调用
- 已适配 Qwen、Zhipu、DeepSeek、OpenAI、AIPing 等供应商类型
- 支持全局 LLM 配置，工作流节点可以引用已有配置
- 普通用户只能读取脱敏后的模型配置，管理员可新增、修改、删除配置

### TTS + MinIO 音频节点

TTS 保留为可选增强，用于朗读最终答案或做语音问答演示：

- 超过 600 字符的文本会按标点智能分段
- 每个文本分段独立调用 TTS API
- 下载多个音频片段并合并为单个 WAV 文件
- 上传合并后的音频到 MinIO
- 返回可直接访问的预签名音频 URL

### RAG 知识库节点

- 支持创建用户级知识库
- 支持粘贴导入文本内容，也支持本地 `txt / md / json / pdf / doc / docx` 文件上传
- 后端自动按长度和标点进行文本切片
- 支持可插拔 Embedding Provider：默认本地 Hash Embedding，可切换 DashScope `text-embedding-v4`
- 支持 Qdrant 向量索引进行语义检索
- 支持知识库向量索引重建，避免切换 Embedding 模型后新旧向量混用
- 使用向量相似度、关键词匹配和 rerank 分数召回相关知识片段
- RAG 节点可只召回相关知识片段，也可将 `context + question` 拼入 LLM 提示词生成回答
- RAG 输出包含结构化 `citations`，发布页面会展示来源文件、页码、分数和片段预览
- 节点执行过程通过 `NODE_PROGRESS` 推送“检索中 / 检索完成”等状态

### 工作流发布与 API 调用

- 支持把已保存的工作流发布为公开页面，页面地址形如 `/p/{shareKey}`
- 发布后同时生成正式 API 地址，形如 `/api/published-workflows/{shareKey}/execute-api`
- 正式 API 需要在请求头传入 `X-PaiAgent-Api-Key`，避免公开链接被直接当作无限制 API 使用
- 编辑器发布弹窗会展示公开页面、API 地址、API 访问密钥和 `curl` 示例
- 公开页面适合人工演示和轻量使用，正式 API 适合外部系统集成

API 调用示例：

```bash
curl -X POST "http://localhost:5174/api/published-workflows/{shareKey}/execute-api" \
  -H "Content-Type: application/json" \
  -H "X-PaiAgent-Api-Key: {apiAccessKey}" \
  -d "{\"inputData\":\"请根据知识库介绍这个项目\"}"
```

### Workflow Test Harness

- 编辑器新增“测试集”入口，保存当前工作流后可维护一组回归测试用例
- 测试用例支持配置输入文本、期望执行状态、必须包含/不能包含的关键词、最大耗时
- 针对 RAG / TTS 场景支持专项断言：要求输出包含 `citations`，或要求生成 `audioUrl`
- 后端复用真实工作流引擎批量执行用例，并记录测试运行、单用例结果和断言明细
- 适合在发布工作流前做最小回归验证，避免提示词、节点引用、RAG 配置或 TTS 配置改动后才在公开页面暴露问题

### RAG Embedding 配置

默认使用 DashScope `text-embedding-v4` 生成真实语义向量。API Key 会按以下顺序读取：
`RAG_EMBEDDING_API_KEY -> Qwen_API_KEY -> QWEN_API_KEY -> DASHSCOPE_API_KEY -> API_KEY`。

```env
RAG_EMBEDDING_PROVIDER=dashscope
RAG_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
RAG_EMBEDDING_MODEL=text-embedding-v4
RAG_EMBEDDING_DIMENSIONS=1024
RAG_EMBEDDING_BATCH_SIZE=16
```

如果要显式指定阿里云百炼 Key，可以在 `backend/.env` 或根目录 `.env` 中配置：

```env
RAG_EMBEDDING_API_KEY=your_dashscope_api_key
```

查询链路支持 query embedding 本地缓存、外部 embedding 调用限流和 429 重试，可按需调整：

```env
RAG_EMBEDDING_CACHE_ENABLED=true
RAG_EMBEDDING_CACHE_TTL_SECONDS=3600
RAG_EMBEDDING_CACHE_MAX_SIZE=2048
RAG_EMBEDDING_MAX_CONCURRENT_REQUESTS=4
RAG_EMBEDDING_RATE_LIMIT_PERMITS_PER_SECOND=4
RAG_EMBEDDING_RETRY_MAX_ATTEMPTS=3
```

切换 Embedding Provider 或模型后，需要在前端 RAG 节点配置区点击“重建向量索引”，让历史知识库切片重新生成向量。

### RAG VectorStore 配置

RAG 向量检索使用 Qdrant，配置示例：

```env
QDRANT_URL=http://localhost:6333
QDRANT_API_KEY=
QDRANT_COLLECTION_PREFIX=paiagent_chunks
QDRANT_TIMEOUT_MS=30000
```

Qdrant collection 会按 Embedding 类型和维度自动隔离，例如 `paiagent_chunks_local_256`、`paiagent_chunks_dashscope_1024`，避免不同维度的向量混写。

### 实时执行调试

- 后端通过 SSE 推送执行事件
- 前端调试面板展示工作流开始、节点开始、节点成功、工作流完成等状态
- 支持查看每个节点输入、输出、耗时和最终结果

![实时调试](image/README-6e538662bb834dbcad888ef065d28bea.png)

### 用户与权限

- 新增注册和登录接口
- 密码使用 BCrypt 加密存储
- JWT 负责访问令牌，Redis 存储 refresh token
- 支持 `ADMIN` / `USER` 两类角色
- 普通用户只能访问自己的工作流
- 管理员可管理全局 LLM 配置

### API Key 加密存储

- 数据库中的模型 API Key 使用 AES/GCM 加密保存
- 接口返回时按角色做脱敏或解密
- 工作流 JSON 中的节点级 `apiKey` 也会在保存时加密
- 项目启动时会自动迁移旧明文 API Key

### Docker Compose 一键部署

- `scripts/docker-up-full.ps1` 一键启动演示环境：先启动独立 Qdrant，再启动 PaiAgent 前端、后端、MySQL、Redis、MinIO
- `scripts/docker-up-app.ps1` 只启动 PaiAgent 前后端，连接已有 MySQL、Redis、MinIO、Qdrant
- `scripts/docker-up-qdrant.ps1` 单独启动 Qdrant，容器名为 `qdrant`，不再挂在 `paiagent-main` 应用组下
- `scripts/docker-import-local-data.ps1` 可把旧的本机 MySQL 数据导入 Docker MySQL，用于复刻非 Docker 开发环境里的工作流和配置
- 前端使用 Nginx 托管静态资源，并反向代理 `/api`
- 支持本机演示和局域网访问，详细说明见 [docs/deploy-compose.md](docs/deploy-compose.md)

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.4.1、MyBatis-Plus、Spring Security Crypto |
| AI 调用 | Spring AI、OpenAI Compatible API、DashScope / Qwen、Zhipu GLM |
| 工作流 | 自研 DAG 引擎、LangGraph4j 状态图引擎 |
| 数据库 | MySQL |
| 向量库 | Qdrant |
| 缓存 | Redis |
| 文件存储 | MinIO |
| 前端 | React 18、TypeScript、Vite、ReactFlow、Ant Design、Zustand |
| 部署 | Docker Compose、Nginx |

## 系统架构

```text
Frontend
  React + ReactFlow + Ant Design
        |
        | REST / SSE
        v
Backend
  Spring Boot
  Auth / Workflow / LLM Config / Execution
        |
        | MyBatis-Plus
        v
MySQL

Backend -> Redis      : refresh token / runtime cache
Backend -> MinIO      : audio file storage
Backend -> MySQL      : workflow / execution / knowledge base / chunk metadata
Backend -> Qdrant     : vector index / topK semantic retrieval
Backend -> LLM / TTS  : model provider API
```

## 工作流执行流程

```text
1. 前端在 ReactFlow 画布上编辑节点和边
2. 保存时将工作流 JSON 写入 MySQL
3. 执行时后端读取 workflow.flow_data
4. DAGParser 解析节点依赖并做拓扑排序
5. WorkflowEngine 按顺序调用对应 NodeExecutor
6. 每个节点输出写入上下文并传给下游节点
7. SSE 将执行事件实时推送到前端调试面板
8. 执行记录写入 execution_record
```

## 本地开发

### 环境要求

- JDK 21
- Maven 3.9+
- Node.js 20+
- MySQL 8+
- Redis
- MinIO

### 后端配置

复制环境变量模板：

```powershell
Copy-Item backend/.env.example backend/.env
```

按本地环境修改 `backend/.env`：

```text
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=paiagent
MYSQL_USERNAME=root
MYSQL_PASSWORD=123456

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=123456

MINIO_ENDPOINT=http://localhost:9000
MINIO_PUBLIC_URL=http://localhost:9000

JWT_SECRET=change_me_to_a_long_random_string
API_KEY_ENCRYPTION_SECRET=change_me_to_another_long_random_string
```

初始化数据库：

```powershell
mysql -u root -p paiagent < backend/src/main/resources/schema.sql
```

启动后端：

```powershell
cd backend
mvn spring-boot:run
```

Swagger 地址：

```text
http://localhost:8085/swagger-ui.html
```

### 前端配置

复制环境变量模板：

```powershell
Copy-Item frontend/.env.example frontend/.env.local
```

本地开发推荐：

```text
VITE_API_BASE_URL=/api
VITE_API_PROXY_TARGET=http://localhost:8085
```

启动前端：

```powershell
cd frontend
npm install
npm run dev
```

访问地址：

```text
http://localhost:5173
```

默认管理员：

```text
admin / admin123
```

## Docker Compose 部署

项目提供两种 Compose 模式：

- `docker-compose.yml`：完整演示版，启动前端、后端、MySQL、Redis、MinIO、Qdrant，适合新电脑一键运行。
- `docker-compose.app.yml`：外部依赖版，只启动前端、后端，连接已有 MySQL、Redis、MinIO、Qdrant。

完整演示版：

```powershell
docker compose up -d --build
```

访问地址：

```text
前端：http://localhost:5174
后端 Swagger：http://localhost:8085/swagger-ui.html
MinIO Console：http://localhost:9003
Qdrant：http://localhost:6333/dashboard
```

外部依赖版：

```powershell
Copy-Item .env.external.example .env.external
docker compose --env-file .env.external -f docker-compose.app.yml up -d --build
```

外部依赖版默认访问：

```text
前端：http://localhost:5174
后端 Swagger：http://localhost:8085/swagger-ui.html
```

如果只缺少独立 Qdrant，可以单独启动：

```powershell
docker compose -f docker-compose.qdrant.yml up -d
```

如果需要局域网访问，修改根目录 `.env`：

```text
MINIO_PUBLIC_URL=http://你的局域网IP:9000
```

其他电脑访问：

```text
http://你的局域网IP:5173
```

更多说明见 [Docker Compose 部署文档](docs/deploy-compose.md)。

## 推荐演示流程

1. 登录系统，确认已有默认知识库 `企业服务台示例知识库`。
2. 打开默认工作流 `企业服务台助手`，或在编辑器新建企业服务台模板。
3. 工作流主线为 `Input -> RAG -> LLM -> Condition -> Output`。
4. LLM 节点选择全局模型配置，默认使用 `service-desk-answer` skill；无 LLM Key 时，本地企业 skill 可走结构化 demo fallback，便于离线演示。
5. 输入示例问题：`我连不上公司 VPN，提示证书过期，怎么办？`
6. 点击调试，观察 RAG 命中片段、结构化 JSON 输出和业务卡片展示。
7. 点击发布，复制公开页面进行演示，复制 API 地址和访问密钥给外部程序调用。

## 可展示版本验收

建议在对外演示或继续改 UI 前先跑一遍最小闭环：

```powershell
.\scripts\smoke-service-desk.ps1
```

脚本会依次执行：

1. `backend` 下运行 `mvn test`。
2. `frontend` 下运行 `npm run build`。
3. 如果 `http://localhost:8085` 没有可用后端，会临时执行 `mvn spring-boot:run` 并在结束后停止。
4. 使用默认管理员 `admin / admin123` 登录，查找默认工作流 `企业服务台助手`。
5. 真实提交示例问题 `我连不上公司 VPN，提示证书过期，怎么办？`。
6. 断言工作流返回 `SUCCESS`，且最终 `outputData` 仍是业务对象，不是 JSON 字符串。
7. 断言业务对象包含 `answer / citations / confidence / resolved / nextAction`，并且 VPN 示例问题只展示相关引用来源。

如果后端已由 Docker 或本地进程启动，可以禁止脚本自动启动后端：

```powershell
.\scripts\smoke-service-desk.ps1 -NoAutoStartBackend
```

如果只想复测 API 闭环，可以跳过耗时较长的测试和前端构建：

```powershell
.\scripts\smoke-service-desk.ps1 -SkipBackendTests -SkipFrontendBuild
```

脚本会在执行前检查默认服务台工作流的 LLM 节点。如果节点缺少 `configId / provider`，会从全局 LLM 配置中按 `deepseek, qwen, zhipu, openai, moonshot, mimo` 顺序选择可用配置并写回工作流，不会打印或写死 API Key。

脚本会真实执行一次工作流，因此会新增一条执行记录；它不会删除数据库、volume 或工作流配置。

### RAG 检索压测

如果只验证 RAG retrieval-only 链路，不希望混入 LLM 生成耗时，可以运行：

```powershell
.\scripts\benchmark-rag.ps1 -Total 120 -Concurrency 12 -Scenario repeat-faq
```

也可以使用基本不命中 query embedding 缓存的场景，观察外部 embedding API 限流、排队和重试后的端到端耗时：

```powershell
.\scripts\benchmark-rag.ps1 -Total 60 -Concurrency 12 -Scenario mostly-uncached
```

脚本会自动登录、查找 `retrievalOnly=true` 的 RAG 工作流并输出 `success / failed / p50 / p95 / p99 / throughputRps` 等指标。当前本地 Docker 环境验证中，`repeat-faq` 场景 12 并发 120 次请求成功率 100%，P95 约 137ms；`mostly-uncached` 场景 12 并发 60 次请求成功率 100%，P95 约 5997ms。该结果只代表本地评测环境，不是生产 SLA。

## 已完成的增强

- 完成本地后端、前端、MySQL、Redis、MinIO 联调
- 完成 Qwen / Zhipu LLM 节点调用验证
- 完成 `Input -> RAG -> LLM -> Condition -> Output` 企业服务台默认工作流链路
- 修复 MinIO 预签名 URL 在浏览器侧不可访问的问题
- 增加工作流删除、二次确认和列表刷新
- 增加注册、登录、角色权限和用户工作流隔离
- 增加 BCrypt 密码加密
- 增加 API Key AES/GCM 加密存储和启动迁移
- 增加执行可靠性基础版：运行中状态记录、节点超时、失败重试、错误日志、重试事件推送
- 增加 DAG if/else 条件分支节点：支持 true / false 出口、上游参数引用和动态分支执行
- 跑通 LangGraph4j conditional edge 条件分支和基础循环执行，并补充自动化测试
- 增加 RAG 知识库节点：知识库创建、文本导入、自动切片、可插拔 Embedding、Qdrant 向量索引、相似度检索、向量重建和 LLM 回答
- 增加 RAG 文件解析、异步导入和引用来源展示：支持 `txt / md / json / pdf / doc / docx`，输出来源文件、页码、分数和片段预览
- 增加 Workflow Test Harness：支持工作流级测试用例、批量运行、关键词/RAG 引用/TTS 音频/耗时断言和历史结果记录
- 增加工作流发布能力：支持公开页面、受 API Key 保护的正式调用接口和调用示例
- 增加 Docker Compose、Dockerfile、Nginx 配置和部署文档
- 增加 RAG 查询链路稳定性优化：query embedding 缓存、外部 embedding 调用令牌桶限流、429 指数退避重试和可复现 benchmark 脚本

## 当前边界

- DAG 引擎是当前主要稳定路径，适合常规工作流执行
- LangGraph4j 已支持条件分支和基础循环，但复杂 Agent 状态流、人工中断、检查点恢复仍属于后续增强方向
- RAG 默认使用 DashScope `text-embedding-v4`，需要配置可用 API Key；生产环境还可继续接入 BGE / OpenAI Embedding
- RAG 使用 Qdrant 进行语义向量检索，搭配 MySQL 关键词匹配做 Hybrid 召回；超大规模知识库可继续升级 Milvus
- RAG 已支持常见文档格式解析，但检索质量仍依赖 Embedding Provider、chunk 参数和知识库内容质量
- 企业服务台 skill 支持本地结构化 demo fallback，用于无 LLM Key 的演示和验收；生产环境应接入真实 LLM Provider，并按企业知识源与权限重新配置
- TTS / ai-podcast 只作为语音朗读或内容生成增强，不影响企业服务台主线
- Workflow Test Harness 会调用真实 LLM / RAG / TTS 链路，适合做发布前回归验证；使用 fallback 的用例只代表本地结构化演示，不代表真实模型质量
- 发布 API 已有访问密钥保护，生产环境还应继续补限流、调用审计和密钥轮换
- README、`.env.example` 和 Compose 示例中的 `admin123`、`123456`、`minioadmin` 等弱密码 / 默认凭证仅用于本地 demo，生产部署必须替换为强随机密钥并关闭或修改默认管理员
- 当前测试和本地启动会提示 SLF4J 多绑定、Commons Logging discovery，以及 devtools / PowerShell 重定向下的中文日志乱码；这些属于本地开发边界，生产镜像应统一日志依赖并关闭 devtools
- 执行中断、工作流发布版本、执行快照等高级可靠性能力仍可继续扩展

## 简历描述参考

> **项目名称**：PaiAgent Java AI Workflow (基于 Java 21 + ReactFlow 的双引擎 AI 工作流与 RAG 平台)  
>   
> **项目描述**：  
> 本项目是专为企业智能服务台与知识库问答设计的可视化 AI Agent 工作流编排系统。通过 ReactFlow 拖拽组合 `Input -> RAG -> LLM -> Condition -> Output` 节点，结合企业私有知识库生成规范答复，并按置信度自动进行直接回答、生成工单摘要或升级人工客服。  
>   
> **核心职责与技术要点**：  
> 1. **高并发虚拟线程调优**：基于 **Java 21 + Spring Boot 3** 开启 **Virtual Threads (虚拟线程)** 支持。在多路并发执行流图、高频调用第三方 LLM/RAG API 等 I/O 密集型场景下，极大降低系统线程开销，提升服务吞吐量。  
> 2. **双引擎工作流编排**：  
>    - 设计实现 **DAG 引擎**：使用 **Kahn 拓扑排序算法** 确定节点执行链，引入 **DFS 深度优先搜索算法** 实现图循环依赖检测，保障流图运行可靠性。  
>    - 整合 **LangGraph4j 状态图引擎**：支持复杂 Agent 的条件分支（Conditional Edge）与状态反馈循环，提供最大迭代次数熔断保护。  
> 3. **RAG 向量检索与混合召回**：  
>    - 向量语义检索统一使用 **Qdrant** ANN 索引，关键词检索使用 MySQL `LIKE` 查询，两路召回后加权融合重排。  
>    - 整合 DashScope / local 嵌入模型，实现文本切片（Chunking）、向量索引重建（Reindex）及召回分数的 Hybrid 混合检索。  
> 4. **企业级工程化与数据安全**：  
>    - 设计**敏感配置加密体系**：对敏感数据（大模型 API Key、全局配置等）在库中进行 **AES/GCM** 对称加密，在接口返回时对不同角色做脱敏处理，提供启动时明文 key 自动平滑迁移。  
>    - 完善执行可靠性，引入流图节点超时（Timeout）、指数退避重试（Retry with Backoff）以及基于 **Server-Sent Events (SSE)** 的流式执行状态推送。  
>    - 实现基于 JWT + Redis 刷新令牌的账户体系与权限控制，支持 Docker Compose + Nginx 一键容器化部署。

## 后续规划

项目当前按“企业内部服务台 / 知识流程助手 + 可视化 AI 工作流编排 + RAG 知识库 + 工作流发布调用平台”收口，不再继续横向扩展大量新节点。后续只保留三类必要增强：

- RAG 检索质量：优化 chunk overlap、rerank、引用展示和多文档召回效果
- 发布调用安全：增加调用次数统计、限流、API Key 轮换和调用审计
- 演示部署闭环：完善示例工作流、截图、环境变量说明和本地/外部部署说明

## 项目来源

本项目基于 MIT License 开源项目 [itwanger/PaiAgent](https://github.com/itwanger/PaiAgent) 进行学习复现与二次开发。当前仓库重点展示 Java 大模型应用开发、工作流编排、模型配置安全、对象存储和容器化部署等工程实践。

## License

MIT License
