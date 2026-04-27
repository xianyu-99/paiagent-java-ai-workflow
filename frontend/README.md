# PaiAgent Frontend

AI Agent 流图执行面板前端项目

## 技术栈

- React 18
- TypeScript
- Vite
- ReactFlow (流程图编辑器)
- Ant Design (UI 组件库)
- Tailwind CSS (样式框架)
- Zustand (状态管理)
- Axios (HTTP 客户端)
- React Router (路由管理)

## 快速开始

### 1. 安装依赖

```bash
npm install
```

### 2. 启动开发服务器

```bash
cp .env.example .env.local
npm run dev
```

应用将在 http://localhost:5173 启动

默认会连接 `http://localhost:8084` 后端。如果后端地址有变化，修改 `frontend/.env.local`：

```bash
VITE_API_BASE_URL=http://localhost:8084
```

### 3. 构建生产版本

```bash
npm run build
```

## 项目结构

```
src/
├── api/              # API 接口
├── pages/            # 页面组件
├── components/       # 可复用组件
├── store/            # 状态管理
├── utils/            # 工具函数
├── App.tsx           # 根组件
└── main.tsx          # 入口文件
```

## 功能特性

### 已完成
- ✅ 用户登录/登出
- ✅ 路由守卫
- ✅ 认证状态管理
- ✅ API 请求封装

### 开发中
- 🚧 工作流编辑器
- 🚧 节点面板
- 🚧 画布区域
- 🚧 调试抽屉
- 🚧 节点配置面板

## 环境变量

后端 API 地址通过 Vite 环境变量配置，示例见 `.env.example`：

```bash
VITE_API_BASE_URL=http://localhost:8084
```

## 默认账户

- 用户名: admin
- 密码: 123
