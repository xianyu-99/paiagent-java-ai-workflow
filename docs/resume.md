# 简历描述参考

## 项目名称

PaiAgent：Java 大模型工作流编排与 RAG 知识库平台

## 项目简介

基于 Java 21、Spring Boot、ReactFlow 和 Spring AI OpenAI 兼容接口实现的类 Dify 可视化 AI 工作流平台，支持通过拖拽节点编排 LLM、TTS、条件分支、RAG 知识库等能力，并提供 DAG / LangGraph 双执行引擎、SSE 实时调试、API Key 加密、Docker Compose 一键部署。

## 技术栈

Java 21、Spring Boot 3.4、MyBatis-Plus、MySQL、Redis、MinIO、Qdrant、Spring AI、LangGraph4j、React 18、TypeScript、ReactFlow、Ant Design、Zustand、Docker Compose。

## 可写亮点

- 设计并实现可视化 AI 工作流编排平台，支持 Input、LLM、Condition、RAG、TTS、Output 等节点拖拽编排，后端将前端 ReactFlow JSON 解析为可执行工作流。
- 实现 DAG 工作流引擎，基于拓扑排序确定节点执行顺序，并支持节点超时、失败重试、执行状态记录和 SSE 实时调试事件推送。
- 接入 LangGraph4j 状态图引擎，支持条件分支和循环类工作流，为复杂 Agent 状态流提供扩展能力。
- 抽象 LLM 统一调用层，通过全局模型配置动态适配 OpenAI、DeepSeek、Qwen、智谱等 OpenAI 兼容接口，并对 API Key 做 AES/GCM 加密存储和脱敏返回。
- 实现 RAG 知识库模块，支持 TXT、Markdown、PDF、DOCX 上传解析，按标题、页码、段落和标点进行 chunk 切分，并保存来源文件、章节、页码、offset 等元数据。
- 实现异步知识导入任务，后台完成解析、切片、Embedding、入库和向量索引写入，前端轮询展示导入阶段、百分比和 chunk 处理进度。
- 实现混合检索与本地 Rerank，将向量召回和关键词召回合并，根据 vector score 与 keyword score 综合排序，提高 RAG 命中稳定性。
- 集成 Qwen3-TTS 和 MinIO，支持长文本按标点分片、并发调用 TTS、合并 WAV 文件并上传对象存储，最终返回可播放的预签名音频 URL。
- 提供 Docker Compose 编排 MySQL、Redis、MinIO、Qdrant、后端和前端，便于本地复现和局域网演示。

## 简历一段式写法

实现一个类 Dify 的 Java 大模型应用编排平台，基于 Spring Boot + ReactFlow 支持 LLM、RAG、TTS、条件分支等节点的可视化拖拽编排；后端自研 DAG 执行引擎并接入 LangGraph4j，支持 SSE 实时调试、节点超时重试、执行记录追踪；统一适配 OpenAI 兼容模型接口并实现 API Key 加密存储；实现 RAG 知识库的 PDF/DOCX 解析、异步导入进度、向量检索、关键词召回和本地 Rerank；通过 Docker Compose 编排 MySQL、Redis、MinIO、Qdrant 和前后端服务，支持一键复现和演示。
