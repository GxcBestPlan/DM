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
curl http://localhost:8080/api/hello    # 基础接口
curl http://localhost:8080/api/items    # 数据库 CRUD（列表）
```

## 结构

```
src/main/java/com/dm/backend/
├── BackendApplication.java            # 启动类
├── controller/
│   ├── HelloController.java           # 示例接口 /api/hello
│   └── ItemController.java            # 数据库集成演示 /api/items
├── entity/Item.java                   # JPA 实体（示例）
└── repository/ItemRepository.java     # Spring Data JPA 仓库
src/main/resources/application.yml     # 配置（端口 8080 + 数据源）
```

Maven 代理已配置在 `~/.m2/settings.xml`（走本机代理 127.0.0.1:7890）。
