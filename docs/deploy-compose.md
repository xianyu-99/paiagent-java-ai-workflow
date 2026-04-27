# Docker Compose 部署

项目提供两种 Compose 启动方式：

- 完整演示版：`docker-compose.yml`，启动前端、后端、MySQL、Redis、MinIO、Qdrant，适合新电脑一键运行。
- 外部依赖版：`docker-compose.app.yml`，只启动前端和后端，连接你已有的 MySQL、Redis、MinIO、Qdrant。

## 完整演示版

适合没有本地基础服务的机器：

```powershell
docker compose up -d --build
```

访问地址：

- 前端：http://localhost:5173
- 后端 Swagger：http://localhost:8085/swagger-ui.html
- MinIO Console：http://localhost:9001
- Qdrant：http://localhost:6333

默认管理员：

```text
admin / admin123
```

完整演示版会创建这些 Docker named volume：

```text
paiagent_mysql_data
paiagent_redis_data
paiagent_minio_data
paiagent_qdrant_data
```

停止服务但保留数据：

```powershell
docker compose down
```

删除容器和数据卷：

```powershell
docker compose down -v
```

`down -v` 会删除 MySQL、Redis、MinIO、Qdrant 的演示数据，执行前确认不需要保留。

## 外部依赖版

适合已经有公共基础服务的开发机或部署环境。它只启动：

- `paiagent-backend`
- `paiagent-frontend`

它不会启动项目专属 MySQL、Redis、MinIO、Qdrant。

### 1. 准备外部服务

确保这些服务已经可从宿主机访问：

```text
MySQL  127.0.0.1:3306
Redis  127.0.0.1:6379
MinIO  http://127.0.0.1:9000
Qdrant http://127.0.0.1:6333
```

如果只有 Qdrant 没有单独服务，可以启动独立 Qdrant：

```powershell
docker compose -f docker-compose.qdrant.yml up -d
```

这个 Qdrant 不属于 PaiAgent 应用 Compose，容器名是 `qdrant`，数据卷是 `ai-infra_qdrant_data`。

如果你之前已经用完整演示版导入过 Qdrant 数据，可以复用旧数据卷启动独立 Qdrant：

```powershell
$env:QDRANT_VOLUME_NAME="paiagent-main_paiagent_qdrant_data"
docker compose -f docker-compose.qdrant.yml up -d
```

### 2. 准备外部环境变量

复制模板：

```powershell
Copy-Item .env.external.example .env.external
```

按你的本机服务修改 `.env.external`。在 Docker Desktop 下，容器访问宿主机服务要用：

```text
host.docker.internal
```

例如：

```env
EXTERNAL_MYSQL_HOST=host.docker.internal
EXTERNAL_MYSQL_PORT=3306
EXTERNAL_REDIS_HOST=host.docker.internal
EXTERNAL_REDIS_PORT=6379
EXTERNAL_MINIO_ENDPOINT=http://host.docker.internal:9000
EXTERNAL_MINIO_PUBLIC_URL=http://localhost:9000
EXTERNAL_QDRANT_URL=http://host.docker.internal:6333
```

如果外部 MySQL 还没有 `paiagent` 数据库，先创建并导入 schema：

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -p -e "CREATE DATABASE IF NOT EXISTS paiagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h 127.0.0.1 -P 3306 -u root -p paiagent < backend/src/main/resources/schema.sql
```

### 3. 启动 PaiAgent 应用

```powershell
docker compose --env-file .env.external -f docker-compose.app.yml up -d --build
```

访问地址：

- 前端：http://localhost:5174
- 后端 Swagger：http://localhost:8085/swagger-ui.html

验证 Qdrant 是外部服务：

```powershell
docker compose --env-file .env.external -f docker-compose.app.yml ps
docker ps --filter "name=qdrant"
```

### 4. 停止外部依赖版

只停止 PaiAgent 前后端：

```powershell
docker compose --env-file .env.external -f docker-compose.app.yml down
```

这不会停止你的外部 MySQL、Redis、MinIO、Qdrant。

如果单独启动了 `docker-compose.qdrant.yml`，停止 Qdrant：

```powershell
docker compose -f docker-compose.qdrant.yml down
```

删除独立 Qdrant 数据：

```powershell
docker compose -f docker-compose.qdrant.yml down -v
```

## 局域网演示

完整演示版和外部依赖版都需要保证 `MINIO_PUBLIC_URL` 是浏览器能访问的地址。

查询本机局域网 IP：

```powershell
ipconfig
```

完整演示版修改 `.env`：

```text
MINIO_PUBLIC_URL=http://你的局域网IP:9000
```

外部依赖版修改 `.env.external`：

```text
EXTERNAL_MINIO_PUBLIC_URL=http://你的局域网IP:9000
```
