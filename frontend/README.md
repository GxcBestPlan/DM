# frontend

DM 项目前端：Vite + Vue 3，包管理器使用 npm。

## 本地开发

```sh
npm install      # 安装依赖
npm run dev      # 启动开发服务器（默认 http://localhost:5173）
npm run build    # 生产构建（输出到 dist/）
npm run preview  # 预览生产构建
```

## 与后端联调

开发服务器已配置代理（`vite.config.js`）：所有 `/api` 开头的请求转发到 `http://localhost:8080`（后端 Spring Boot 默认端口），无需处理跨域。

先启动后端（见 `../backend/README.md`），再 `npm run dev`，首页会自动调用 `/api/hello` 验证连通。
