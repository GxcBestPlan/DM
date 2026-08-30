# backend

DM 项目后端：Spring Boot 2.7.18（兼容 JDK 8）+ Maven。

> 注意：JDK 8 无法运行 Spring Boot 3.x，本项目固定使用 Spring Boot 2.7.x。

## 环境要求

- JDK 8（本机已安装 Zulu 8，见 `~/.zshrc` 中 `JAVA_HOME`）
- Maven 3.9+（本机已安装 `~/tools/apache-maven-3.9.16`）

## 本地运行

```sh
mvn spring-boot:run          # 启动（默认 http://localhost:8080）
mvn compile                  # 仅编译
mvn test                     # 运行测试
mvn package                  # 打包可执行 jar
```

启动后验证：

```sh
curl http://localhost:8080/api/hello
```

## 结构

```
src/main/java/com/dm/backend/
├── BackendApplication.java        # 启动类
└── controller/HelloController.java # 示例接口 /api/hello
src/main/resources/application.yml  # 配置（端口 8080）
```

Maven 代理已配置在 `~/.m2/settings.xml`（走本机代理 127.0.0.1:7890）。
