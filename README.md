# DM

前后端分离项目。

## 项目结构

```
DM/
├── frontend/   # 前端：Vite + Vue 3（npm）
└── backend/    # 后端：Spring Boot 2.7.18（JDK 8）+ Maven
```

| 目录 | 技术栈 | 端口 |
|---|---|---|
| `frontend/` | Vite 8 + Vue 3.5，npm | 5173（开发服务器） |
| `backend/` | Spring Boot 2.7.18，JDK 8，Maven | 8080 |

## 快速开始

### 1. 启动后端

```sh
cd backend
mvn spring-boot:run
# 验证: curl http://localhost:8080/api/hello
```

### 2. 启动前端

```sh
cd frontend
npm install
npm run dev
# 打开 http://localhost:5173，首页自动调用后端 /api/hello 验证连通
```

开发时前端通过 Vite 代理把 `/api` 请求转发到后端 8080，无需处理跨域。

## 环境要求

- 前端：Node.js ≥ 20（本机 24.x）
- 后端：JDK 8（本机 Zulu 8）+ Maven 3.9+（本机 3.9.16）

> 注意：JDK 8 无法运行 Spring Boot 3.x，因此后端固定使用 Spring Boot 2.7.x。

## 开发

详细说明见各子目录 README：`frontend/README.md`、`backend/README.md`。
