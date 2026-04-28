# Docker Compose 部署

项目现在拆成三种启动方式，避免把 Qdrant 混在 PaiAgent 应用组里：

- `scripts/docker-up-full.ps1`：一键启动演示环境。会启动独立 Qdrant，再启动 PaiAgent 前端、后端、MySQL、Redis、MinIO。
- `scripts/docker-up-app.ps1`：只启动 PaiAgent 前端和后端，连接你已有的 MySQL、Redis、MinIO、Qdrant。
- `scripts/docker-up-qdrant.ps1`：只启动独立 Qdrant。

## 一键演示环境

适合新电脑或想完整复现项目时使用：

```powershell
.\scripts\docker-up-full.ps1
```

这个脚本会先执行：

```powershell
docker compose -f docker-compose.qdrant.yml up -d
```

再执行：

```powershell
docker compose up -d --build --remove-orphans
```

默认访问地址：

- 前端：http://localhost:5174
- 编辑器：http://localhost:5174/editor
- 后端 Swagger：http://localhost:8085/swagger-ui.html
- MinIO Console：http://localhost:9003
- Qdrant：http://localhost:6333/dashboard

默认管理员：

```text
admin / admin123
```

默认端口：

```text
PaiAgent Frontend  5174 -> 80
PaiAgent Backend   8085 -> 8084
PaiAgent MySQL     3307 -> 3306
PaiAgent Redis     6380 -> 6379
PaiAgent MinIO     9002 -> 9000
MinIO Console      9003 -> 9001
Qdrant             6333 -> 6333
Qdrant gRPC        6334 -> 6334
```

这样不会占用你已有的 `mysql:3306`、`redis:6379`、`minio:9000/9001`。

停止完整演示环境但保留数据：

```powershell
.\scripts\docker-down-full.ps1
```

不要随手执行 `down -v`。`-v` 会删除 MySQL、Redis、MinIO、Qdrant 的数据卷。

### 从本机旧环境导入真实数据

如果你之前没有用 Docker 跑后端，而是用本机已有的 `mysql:3306` 保存过工作流、LLM 配置、知识库等数据，一键演示环境不会自动带上这些数据。启动完整 Docker 后执行：

```powershell
.\scripts\docker-import-local-data.ps1
```

默认导入方向是：

```text
源库：mysql / paiagent / 3306
目标库：paiagent-mysql / paiagent / 3307
```

脚本会先备份目标 Docker 数据库到 `backups/`，再导入这些业务表：

```text
app_user
workflow
execution_record
llm_global_config
knowledge_base
knowledge_document
knowledge_chunk
knowledge_import_task
```

如果容器名、数据库名或密码不同，可以用环境变量覆盖，例如：

```powershell
$env:PAIAGENT_SOURCE_MYSQL_CONTAINER="mysql"
$env:PAIAGENT_TARGET_MYSQL_CONTAINER="paiagent-mysql"
$env:PAIAGENT_SOURCE_MYSQL_PASSWORD="123456"
$env:PAIAGENT_TARGET_MYSQL_PASSWORD="123456"
.\scripts\docker-import-local-data.ps1
```

## 只启动前后端

适合你已经有独立基础设施时使用。它只启动：

- `paiagent-backend`
- `paiagent-frontend`

准备配置：

```powershell
Copy-Item .env.external.example .env.external
```

按你的本机服务修改 `.env.external`：

```env
EXTERNAL_MYSQL_HOST=host.docker.internal
EXTERNAL_MYSQL_PORT=3306
EXTERNAL_REDIS_HOST=host.docker.internal
EXTERNAL_REDIS_PORT=6379
EXTERNAL_MINIO_ENDPOINT=http://host.docker.internal:9000
EXTERNAL_MINIO_PUBLIC_URL=http://localhost:9000
EXTERNAL_QDRANT_URL=http://host.docker.internal:6333
```

启动：

```powershell
.\scripts\docker-up-app.ps1
```

停止：

```powershell
.\scripts\docker-down-app.ps1
```

## 单独启动 Qdrant

如果你已经有 MySQL、Redis、MinIO，只缺 Qdrant：

```powershell
.\scripts\docker-up-qdrant.ps1
```

Qdrant 容器名是 `qdrant`，不再放在 `paiagent-main` 组下。

如果之前用旧的一键 Compose 生成过 `paiagent-main_paiagent_qdrant_data`，脚本会优先复用这个旧数据卷，避免已有向量数据丢失。

## 局域网访问

先查本机局域网 IP：

```powershell
ipconfig
```

假设 IP 是 `192.168.1.20`，局域网访问地址是：

- 前端：http://192.168.1.20:5174
- 编辑器：http://192.168.1.20:5174/editor
- 后端 Swagger：http://192.168.1.20:8085/swagger-ui.html
- MinIO Console：http://192.168.1.20:9003
- Qdrant：http://192.168.1.20:6333/dashboard

音频、文件这类 MinIO URL 需要让浏览器能访问。完整演示环境修改 `.env`：

```env
MINIO_PUBLIC_URL=http://192.168.1.20:9002
```

只启动前后端模式修改 `.env.external`：

```env
EXTERNAL_MINIO_PUBLIC_URL=http://192.168.1.20:9000
```

修改后重启对应容器。
