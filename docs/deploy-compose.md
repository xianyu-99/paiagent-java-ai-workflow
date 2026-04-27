# Docker Compose 部署

## 本机演示

```powershell
docker compose up -d --build
```

访问地址：

- 前端：http://localhost:5173
- 后端 Swagger：http://localhost:8085/swagger-ui.html
- MinIO Console：http://localhost:9001

默认管理员：

```text
admin / admin123
```

## 局域网演示

1. 查询本机局域网 IP：

```powershell
ipconfig
```

2. 如果项目根目录没有 `.env`，复制环境模板：

```powershell
Copy-Item .env.compose.example .env
```

3. 修改根目录 `.env`：

```text
MINIO_PUBLIC_URL=http://你的局域网IP:9000
```

4. 启动：

```powershell
docker compose up -d --build
```

5. 其他电脑访问：

```text
http://你的局域网IP:5173
```

## 停止与清理

停止服务但保留数据：

```powershell
docker compose down
```

删除容器和数据库/Redis/MinIO 数据卷：

```powershell
docker compose down -v
```

`down -v` 会删除本地演示数据，执行前确认不需要保留。
