# PaiAgent Frontend

React + Vite 前端，提供登录、工作流可视化编辑、调试执行、全局 LLM 配置、知识库管理和 RAG 文档导入界面。

## 技术栈

- React 18
- TypeScript
- Vite
- ReactFlow
- Ant Design
- Tailwind CSS
- Zustand
- Axios

## 本地启动

1. 安装依赖：

```powershell
npm install
```

2. 配置后端地址：

```powershell
Copy-Item .env.example .env.local
```

默认 API 地址：

```properties
VITE_API_BASE_URL=http://localhost:8084
```

3. 启动开发服务器：

```powershell
npm run dev
```

访问地址：`http://localhost:5173`

默认开发账号：`admin / admin123`

## 主要页面

- `/login`：登录。
- `/editor`：工作流编辑器。
- `/knowledge`：知识库管理，支持粘贴文本和本地文件导入，并显示异步导入进度。

## 构建

```powershell
npm run build
```
