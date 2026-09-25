# backend

DM 项目后端：Spring Boot 2.7.18（兼容 JDK 8）+ Maven + MySQL 8.0。

> 注意：JDK 8 无法运行 Spring Boot 3.x，本项目固定使用 Spring Boot 2.7.x。

## 环境要求

- JDK 8（本机已安装 Zulu 8，见 `~/.zshrc` 中 `JAVA_HOME`）
- Maven 3.9+（本机已安装 `~/tools/apache-maven-3.9.16`）
- MySQL 8.0（本机已安装 `~/tools/mysql-8.0.46-macos15-arm64`）

## MySQL

本机 MySQL 为免 sudo 安装（`~/tools/mysql-8.0.46-macos15-arm64`）：

- 数据目录：`~/tools/mysql-data`，运行文件：`~/tools/mysql-run`
- 启动：`~/tools/mysql-8.0.46-macos15-arm64/bin/mysqld --basedir=~/tools/mysql-8.0.46-macos15-arm64 --datadir=~/tools/mysql-data --port=3306 --socket=~/tools/mysql-run/mysql.sock --mysqlx=0 &`
- 停止：`mysqladmin -u root -pDm123456 shutdown`
- 连接：`mysql -u dm -pDm123456 -h 127.0.0.1 dm`

| 项目 | 值 |
|---|---|
| 数据库 | `dm`（utf8mb4） |
| 应用用户 | `dm` / `Dm123456`（仅本机） |
| root | `root` / `Dm123456` |

## 本地运行

```sh
mvn spring-boot:run          # 启动（默认 http://localhost:8080）
mvn compile                  # 仅编译
mvn test                     # 运行测试
mvn package                  # 打包可执行 jar
```

启动后验证：

```sh
curl http://localhost:8080/api/hello    # 基础接口（业务接口开发中）
```

## 鉴权与接口

- 除 `/api/auth/login`、`/api/hello` 外，所有 `/api/**` 需带 `Authorization: Bearer <token>`；组织管理接口仅 `ADMIN` 可用（无权限返回 403）。
- 首次启动且库中无任何账号时，自动创建管理员 `admin` / `admin123`（请登录后尽快重置密码）。
- JWT 有效期 12 小时，密钥见 `application.yml` 的 `dm.jwt.secret`（生产部署用环境变量覆盖）。

```sh
# 登录拿 token
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin","password":"admin123"}'

# 带 token 访问
curl -s http://localhost:8080/api/auth/me -H "Authorization: Bearer <token>"

# 组织管理（仅 ADMIN）
curl -s -X POST http://localhost:8080/api/org/teams -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -d '{"name":"交易研发一队","description":"交易链路"}'
```

| 接口 | 说明 |
|---|---|
| `POST /api/auth/login` | 登录，返回 token 与用户信息（含所属小队、队内角色） |
| `GET /api/auth/me` | 当前登录用户（前端刷新后恢复登录态） |
| `GET/POST /api/org/teams`、`PUT/DELETE /api/org/teams/{id}` | 小队查询/新建/改名/删除 |
| `GET/POST /api/org/teams/{id}/members`、`PUT/DELETE .../members/{userId}` | 成员加入/移除、多角色配置 |
| `GET/POST /api/org/users`、`PUT /api/org/users/{id}/status`、`POST /api/org/users/{id}/password` | 账号创建、启用/停用、重置密码 |
| `GET /api/requirements?sprintId=` | 需求列表（不带 sprintId = 需求池；支持 type/status/createdBy/keyword 筛选） |
| `POST /api/requirements`、`PUT /api/requirements/{id}`、`PUT /api/requirements/pool-order` | 录入、编辑、拖拽定序（PM） |
| `POST /api/requirements/{id}/review` | 登记评审结论：通过前验收标准必填；退回意见必填 |
| `POST /api/requirements/{id}/schedule`、`/unschedule`、`/dev`、`/ready-for-test`、`/publish`、`/block`、`/unblock`、`/cancel` | 排入/插队/移出、迭代内推进、阻塞、取消（开发管理者） |
| `POST /api/requirements/{id}/claim`、`/accept`、`/reject` | 测试领取、通过、退回（测试） |
| `GET/POST /api/sprints`、`POST /api/sprints/{id}/start`、`/close`、`/reopen`、`GET /api/sprints/{id}` | 迭代查询/创建/开始/关闭/重开 |
| `GET/POST /api/requirements/{id}/tasks`、`PUT/DELETE /api/tasks/{id}` | 任务拆解、改派、计划日期、删除（开发管理者） |
| `POST /api/tasks/{id}/dependencies`、`DELETE .../dependencies/{dependsOnTaskId}` | 任务依赖（仅前端依赖后端、同需求内、禁环） |
| `POST /api/tasks/{id}/start`、`/complete`、`/progress`、`/block`、`/unblock` | 开发推进（负责人或开发管理者）：首个任务开始→需求转开发中；全部完成→需求转待测试 |
| `PUT /api/tasks/{id}/schedule`、`GET /api/sprints/{id}/pipeline` | 管道排期：人员×日期、同日重叠冲突、未排期任务 |
| `GET /api/tasks/mine?scope=today` | 我的任务（scope 默认 all；today 为进行中 + 今日应开始） |
| `GET /api/requirements?all=true&status=READY_FOR_TEST` | 跨迭代查询（测试工作台队列等） |
| `GET /api/overview` | 跨队总览：各队当前迭代进度、负载、延期风险（仅 supervisor/ADMIN） |
| `GET /api/reports/teams` | 跨队汇总：完成率对比 + 各队延期风险 Top（仅 supervisor/ADMIN） |
| `GET /api/reports/sprints/{id}` | 迭代报告：完成率、按期完成率、状态分布、按天趋势、插队占比、阻塞时长、人均负载 |

> 一个用户只属于一个小队：加入新队时会自动移除旧队的成员行（见 `OrgService.addMember`）。

## 数据库表结构

8 张表由 `src/main/resources/schema.sql` 在启动时自动创建（`spring.sql.init.mode=always`，可重复执行）；`spring.jpa.hibernate.ddl-auto=none`，以脚本为唯一真相，表结构说明见 `../docs/design.md`。

- 业务表：`sys_user`、`team`、`team_member`、`sprint`、`requirement`、`task`、`task_dependency`、`status_log`
- `mvn test` 需先启动本机 MySQL（测试会真实读写 `dm` 库，事务回滚不留数据）

## 结构

```
src/main/java/com/dm/backend/
├── BackendApplication.java            # 启动类
├── controller/HelloController.java    # 示例接口 /api/hello
├── entity/                            # 8 个 JPA 实体 + BaseEntity
│   └── enums/                         # 角色/类型/状态枚举（库内存 code）
└── repository/                        # 8 个 Spring Data JPA 仓库
src/main/resources/
├── application.yml                    # 端口 8080 + 数据源
└── schema.sql                         # 建表脚本（唯一真相）
```

Maven 代理已配置在 `~/.m2/settings.xml`（走本机代理 127.0.0.1:7890）。
