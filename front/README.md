# 通用 Agent 工作台前端

基于 React 与 TypeScript 的通用 Agent 工作台。只有 Draw.io Agent 显示嵌入式绘图画布；通用 Agent、PPT Agent 使用可调整宽度的标准对话布局。

## 功能

- 后端 opaque Bearer Token 登录与用户资源隔离；
- 查询并切换后端 Agent；
- 创建和复用 ADK Session；
- 增量解析 `/api/v1/chat_stream`，完整节点/连线到达后渐进刷新画布；
- PostgreSQL 持久化会话、消息、任务、Checkpoint、上下文快照和运行观测；
- AI 回复支持安全的 Markdown 与 GFM 渲染；
- `/monitor` 在独立页面展示按 Session 查询的 Task、Invocation、Agent、Model、Tool、Step 和上下文压缩；
- `/memories` 管理 PostgreSQL 长期 Memory 与结构化项目状态；
- 基于持久化 Checkpoint 提供审批、停止与继续会话；
- 展示分析、绘图、审核和完成状态；
- 统计生成的节点和连线数量；
- 嵌入 Draw.io 编辑器，支持继续编辑；
- 导出 `.drawio`、SVG 和 PNG；
- 可选自定义模型配置，API Key 仅保存在页面内存；后端也支持 IDEA 环境变量提供默认 Key；
- 响应式工作台和深色模式。

## 环境要求

- Node.js 20.19+ 或 22.12+；当前已在 Node.js 24 下验证；
- Corepack；
- pnpm 10；
- 后端默认运行在 `http://127.0.0.1:8091`。

## 安装

从仓库根目录进入前端：

```powershell
cd front
corepack pnpm install
```

如需创建本地环境配置：

```powershell
Copy-Item .env.example .env.local
```

环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | 空 | 浏览器请求 API 的前缀；开发环境推荐保持为空，使用 `/api` 同源路径 |
| `VITE_API_PROXY_TARGET` | `http://127.0.0.1:8091` | Vite 开发代理目标 |
| `VITE_DRAWIO_BASE_URL` | `https://embed.diagrams.net` | Draw.io Embed 地址，可改为自托管服务 |

不要把模型 API Key 写入 `.env`。模型设置中的 Key 会随每次聊天请求发送给后端，前端只保存在内存中。

## 开发启动

先启动 Java 后端并确认：

```text
http://127.0.0.1:8091/api/v1/query_ai_agent_config_list
```

然后启动前端：

```powershell
cd front
corepack pnpm dev
```

访问：

```text
http://127.0.0.1:5173
```

演示账号：

```text
用户名：admin
密码：admin
```

密码不会保存。后端签发短期 JWT Access Token，并使用 HttpOnly Refresh Cookie 续期；默认 `admin / admin` 只用于本地首次启动，部署前必须修改初始账号与 JWT Secret。

## 验证与构建

```powershell
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm test
corepack pnpm build
```

构建产物位于 `front/dist/`。本地预览：

```powershell
corepack pnpm preview
```

## Nginx 部署

将 `dist/` 内容复制到 Nginx 静态目录，并参考 [nginx.conf.example](./nginx.conf.example) 配置：

- SPA history fallback；
- `/api/` 反向代理到 Java 后端；
- 关闭代理缓冲，确保 NDJSON 实时到达浏览器；
- 将读写超时提高到 360 秒。

示例构建与复制：

```powershell
corepack pnpm build
Copy-Item -Recurse -Force dist\* C:\your-nginx\html\
```

生产部署中建议将 Draw.io 自托管，并把 `VITE_DRAWIO_BASE_URL` 指向受控域名。该变量在构建时写入前端产物，变更后需要重新构建。

## 目录结构

```text
src/
  app/                 应用入口、Provider、路由
  pages/               页面组合层
  features/
    auth/               演示登录与路由保护
    agents/             Agent 查询和选择
    chat/               Session、NDJSON、对话状态与组件
    diagram/            Draw.io 编辑器封装和 XML 状态
    settings/           自定义模型设置
    memory/             长期 Memory API 与类型
  shared/
    api/                通用 HTTP 契约
    config/             环境配置
    styles/             全局样式与设计变量
  test/                 测试初始化
```

依赖方向是 `shared -> features -> pages -> app` 的使用方向：shared 不依赖业务 feature，页面只负责编排，不处理 NDJSON 字节流或 Draw.io iframe 协议。

## 流式协议说明

主接口是：

```http
POST /api/v1/chat_stream
Accept: application/x-ndjson
```

它不是标准 EventSource SSE。前端使用 Fetch `ReadableStream` 和 `TextDecoder`，按照换行符解析独立 JSON 事件。

Draw.io 采用“完整结构增量加载”策略：

```text
drawio_node / drawio_edge -> 组装临时合法 XML，实时更新进度与画布
drawio_done / drawio       -> 校验完整 XML 后原子加载画布
```

首次绘图请求会先返回 `approval` 审核卡片。用户可以确认执行或提交修改；只有确认后的下一轮请求才会进入 Draw.io 生成阶段。

无效 XML 不会覆盖上一张成功图。

## 存储与运行边界

- PostgreSQL 是会话、消息、Task、Invocation、Checkpoint、Memory、Artifact、上下文摘要和观测数据的事实源；
- Redis 用于 Session 执行租约与可重建的短期缓存，不单独保存长期对话；
- 历史列表由服务端数据恢复，浏览器缓存只用于未同步 UI 快照；
- 监控数据持久化，应用重启会把遗留的 `RUNNING` 记录对账为失败，避免无限计时；
- 上下文压缩保留系统提示和能力入口，归档大型 Tool Result 为 Artifact，并保留最近对话、摘要及结构化项目状态；
- 公网部署仍需 TLS、密钥托管、限流、审计保留策略及数据库备份；
- PPT Agent 类型可识别，但当前工作台不渲染 PPT；
- Draw.io 默认依赖公网 `embed.diagrams.net`，离线或受限网络需要自托管；
- 新建会话会清空当前对话，但不会自动清除画布，避免误删用户编辑结果。

后端完整契约见 `../docs/prompt/backend-api.md`。
