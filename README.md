# DM

前后端分离项目：迭代排期与进度管理系统（多小队两周迭代管理）。

## 项目结构

```
DM/
├── docs/        # 需求与设计文档（docs/requirements.md 等）
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
# 打开 http://localhost:5173，用管理员 admin / admin123 登录
```

### 3. 灌入演示数据（可选）

```sh
scripts/seed-demo.sh
# 创建一个小队、6 个角色账号、一个进行中的迭代与若干需求/任务，并打印各角色账号
```

开发时前端通过 Vite 代理把 `/api` 请求转发到后端 8080，无需处理跨域。

## 已实现范围

- **需求池**：录入、编辑、拖拽定序、评审登记（通过前验收标准必填，退回意见必填并留痕）
- **迭代管理**：创建、开始、关闭、重新打开；排入/移出；插队（只提示影响，不自动顺延）
- **任务**：拆解、改派、依赖（仅前端依赖后端、同需求内、禁环、只提示不阻断）、状态推进；首个任务开始自动转"开发中"，全部完成自动转"待测试"
- **管道排期**：人员 × 日期视图、拖拽调整并落库、同一人同日任务重叠高亮
- **测试与验收**：领取、通过、退回（原因必填，退回回到开发中）
- **跨队总览与报表**：各队进度/负载/延期风险、完成率、按期完成率、状态分布、按天趋势、插队占比、阻塞时长、人均负载
- **账号与权限**：JWT 登录、按角色的队内/全局权限校验、组织管理（小队、成员多角色、账号启停与重置密码）

文档：需求 [`docs/requirements.md`](docs/requirements.md)（v1.1）、领域与数据库设计 [`docs/design.md`](docs/design.md)（v1.0，含报表口径）、前端设计语言 [`frontend/README.md`](frontend/README.md)。

## 环境要求

- 前端：Node.js ≥ 20（本机 24.x）
- 后端：JDK 8（本机 Zulu 8）+ Maven 3.9+（本机 3.9.16）

> 注意：JDK 8 无法运行 Spring Boot 3.x，因此后端固定使用 Spring Boot 2.7.x。

## 开发

详细说明见各子目录 README：`frontend/README.md`、`backend/README.md`。
