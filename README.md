# AI Draw.io Agent

基于 Java 17、Spring Boot 4、Google ADK Java 1.7 和 React 的配置驱动式
通用多 Agent 工作台，内置通用任务、Draw.io 与 PPT Agent。Spring Boot 提供 Web 与依赖注入；Agent、模型、
MCP、Skills、Session Event 和工作流均由 Google ADK 承载，运行链路不依赖 Spring AI。

内置 Agent Eval 平台，支持 Dataset、Case、轨迹评分、Baseline 回归比较，并与 Invocation 监控双向关联。启动后访问 `http://localhost:5173/eval`，详见 [Agent Eval 平台](docs/agent-eval-platform.md)。

## 架构基线

- Spring Boot 4.0.2 / Tomcat 11
- Google ADK Java 1.7.0
- ADK `ChatCompletionsHttpClient` 对接 DeepSeek/OpenAI-compatible API
- ADK `SequentialAgent`、`Runner`、Plugin、Session
- ADK `McpToolset`（SSE/stdio）
- ADK `ClassPathSkillSource` / `LocalSkillSource` / `SkillToolset`
- React + TypeScript + Vite + Draw.io Embed
- 内置轻量 Invocation/Agent/Model/Tool/Token/压缩监控
- 支持 PostgreSQL 模板驱动的运行时 ADK Subagent，以及 Agent/Model/Tool 统一瀑布调用链

## 当前进度

项目已完成可运行的前后端主链路，当前处于“功能集成完成、生产验收未完成”阶段：

- 已完成：登录与工作区、会话与流式消息、Draw.io Artifact、Checkpoint/Pause/Resume、长期 Memory、动态 Subagent、Tool/Skill 治理、运行监控和 Agent Eval 基础能力。
- 模型路由：Model Catalog、硬约束过滤、Agent-aware 需求分析和分层 cheapest-sufficient 排序已接管开发环境生产选择；用户显式模型优先，自动路由内部失败时有界回退旧路由器。
- 自动化验证：已有后端路由/治理测试和前端 store/API/stream 测试；双实例恢复、真实 Provider、权限隔离和长上下文仍需要部署环境验收。
- 生产阻断项：开发配置中不得保留真实 API Key 默认值，已暴露的 Key 必须轮换后再部署。

下一步优先级：密钥清理与轮换 → 动态路由真实流量校准及 Provider 健康降级 → 补齐手工测试发现 Bug 的自动回归 → 多实例和权限验收。

文档入口：

- [测试清单](test-checklist.md)
- [Bug 修复记录](fixed_bug.md)
- [Agent Eval 平台](docs/agent-eval-platform.md)
- [Checkpoint Pause/Resume](docs/checkpoint-pause-resume.md)
- [动态 Subagent 与调用链](docs/dynamic-subagent-and-tracing.md)
- [上下文工程](docs/agent-context-engineering.md)
- [模型资料](api-model.md)

后端架构、升级、YAML 装配、监控、上下文治理和项目介绍统一收录在
[ADK 1.7 架构与交付指南](docs/adk-1.7-upgrade.md)，前端说明见 [front/README](front/README.md)。

## PostgreSQL + Redis 启动

PostgreSQL 是用户、会话、消息、Checkpoint、ADK Event、Artifact、上下文快照和运行观测的事实源；Redis 只用于 JWT 注销名单、并发协调和最近消息热缓存。

```powershell
docker compose -f docs/dev-ops/docker-compose.yml up -d
mvn install -DskipTests
cd ai-agent-scaffold-draw-io-app
mvn spring-boot:run -Dmaven.test.skip=true
```

默认数据库账号为 `agent_platform/agent_platform_dev`，默认登录为 `admin/admin`。首次启动由 Flyway 自动迁移。IDEA 的 Run Configuration 建议配置：

```text
DB_URL=jdbc:postgresql://127.0.0.1:5432/agent_platform;DB_USERNAME=agent_platform;DB_PASSWORD=agent_platform_dev;REDIS_HOST=127.0.0.1;REDIS_PORT=6379;JWT_SECRET=replace-with-at-least-32-characters;DEEPSEEK_API_KEY=你的Key
```

前端执行 `cd front; npm install; npm run dev`，访问 `http://localhost:5173`。通用 Agent 只显示会话区；绘图 Agent 额外显示可拖拽调整的 Draw.io Artifact 面板。运行监控在独立页面中按 Session 查询持久化链路。

## 启动后端

环境要求：JDK 17、Maven 3.9+。

```powershell
$env:DEEPSEEK_API_KEY='your-key'
$env:DEEPSEEK_MODEL='deepseek-chat'
mvn clean package
java -jar ai-agent-scaffold-draw-io-app\target\ai-agent-scaffold-draw-io-app.jar
```

默认地址为 `http://127.0.0.1:8091`。验证：

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/v1/auth/login `
  -ContentType application/json -Body '{"username":"admin","password":"admin"}'
$headers = @{ Authorization = "Bearer $($login.data.token)" }
Invoke-RestMethod http://127.0.0.1:8091/api/v1/query_ai_agent_config_list -Headers $headers
Invoke-RestMethod http://127.0.0.1:8091/api/v1/monitor/summary -Headers $headers
```

模型地址也可配置为 `base-url: https://api.deepseek.com` 与
`completions-path: /v1/chat/completions`。

不要把 API Key 写入 YAML 或提交到 Git。历史上暴露过的 Key 应立即轮换。

## 启动前端

环境要求：Node.js 20.19+ 或 22.12+。

```powershell
cd front
npm install
npm run dev
```

访问 `http://127.0.0.1:5173`，初始管理员为 `admin / admin`。后端使用短期 JWT Access Token、
HttpOnly Refresh Cookie、Refresh Token 轮换和 Redis 注销名单。部署前必须修改初始密码和 JWT Secret。
Vite 会把 `/api` 代理到 `http://127.0.0.1:8091`。

## 验证

```powershell
mvn test
mvn package
cd front
npm run build
```

后端契约测试使用本地 HTTP Server，不消耗真实模型额度；覆盖 Chat Completions
URL、Authorization、请求内容、响应文本和 usage token 映射，以及 ADK classpath Skill 加载。

## YAML 能力域

能力源在 `chat-model` 下声明，具体 Agent 通过 `capability-groups` 声明可检索的安全边界。真实 Tool/Skill
不会直接装配给模型；Agent 只固定拥有 `search_capabilities`、`load_capability`、`execute_capability`：

```yaml
chat-model:
  tool-skills-list:
    - name: drawio-skills
      type: resource
      path: agent/skills
      included-skills: [drawio]
agents:
  - name: agent_analyst
  - name: agent_drawer
    capability-groups:
      - drawio-skills
```

执行时模型先从 Registry 检索 Top-K（最多 16 个），获得绑定 Invocation、用户和 Agent 的快照；只有快照内
的能力允许加载与执行。快照 ID 和能力 ID 同时写入 ADK Session State，应用恢复后可重建相同快照。

## 上下文压缩

Runner 默认在约 89,600 tokens 时使用 ADK `LlmEventSummarizer` 压缩历史，并保留
最近 12 个事件。请求达到 85% 上下文窗口后，还有滑动窗口与结构化 Draw.io 项目
状态兜底。相关参数可在 YAML `runner` 中调整。

上下文按三个区域治理：稳定的 Agent Instruction、Tool Schema 与 Skill Catalog 不进入历史窗口压缩；
Tool/MCP Result 超过请求级预算时转换为有标记的摘要；旧对话进入结构化项目状态与历史摘要，最近
6–12 轮保持原文。

## 模型与能力路由

前端显式模型优先。未显式选择时，生产链路使用不额外调用模型的分层动态 Router：先由任务类型确定
Fast、Balanced 或 Reasoning 目标层级，再执行上下文、输出、Vision、Tool Calling 等硬约束过滤，最后在
当前层选择最便宜的充分模型，不充分时才升级。摘要、提取、格式化和简单编辑从 Fast 开始；普通或不确定
任务从 Balanced 开始；诊断、深度分析和 Draw.io 审查从 Reasoning 开始。自动路由失败或无候选时有界
回退旧路由器；Top-3 仅用于评估快照，每个请求实际只选择一个模型。

Skills 使用 ADK `SkillToolset` 的 Catalog → `load_skill` → `load_skill_resource` 按需加载，不把
全部 Skill 正文放入上下文。`included-skills` 可把每个 Agent 可见目录限制在最多 32 个 Skill。
MCP 按业务域拆成工具组，默认必须使用 `included-tools` 白名单；单组最多 64 个 Tool。只有明确接受
全量 Schema 风险时才能设置 `allow-all-tools: true`：

```yaml
tool-mcp-list:
  - name: project-search
    included-tools: [search_code, read_file, list_symbols]
    sse:
      base-uri: http://127.0.0.1:8082
      sse-endpoint: /sse
```

所有动态 Toolset 默认经过运行治理：单次调用 120 秒超时，连续失败 3 次后熔断 60 秒。可通过
`TOOL_TIMEOUT_MS`、`TOOL_FAILURE_THRESHOLD`、`TOOL_COOLDOWN_MS` 调整。

Capability Registry 支持运行时调用 `registerTool`、`registerToolset`、`registerSkill` 热注册；新增 Provider
不需要修改 Agent Prompt。当前检索采用权限过滤、类型过滤、关键词与中英文片段匹配，再按相关性稳定排序。

## 当前生产边界

- 外部 Pi 控制面已经移除；通用 Agent 通过受控模板动态创建原生 ADK Subagent，Pause/Resume 仍由 ADK Session 与业务 Checkpoint 提供；
- PostgreSQL 是用户、长期 Memory、会话、消息、ADK Session/Event、Checkpoint、Artifact 与运行观测的事实源；
- Redis 保存 Refresh/JWT 治理状态与最近消息热缓存，不作为不可恢复数据的唯一存储；
- 监控同时保留进程内实时视图和 PostgreSQL 持久化投影，应用重启会终结遗留的运行状态；
- Tool 自动重试默认关闭，避免对非幂等工具重复产生副作用；每次执行 Attempt 均独立落库，后续只应对声明为幂等的能力开启策略化重试；
- YAML 旧 `local` Spring AI ToolCallback 已移除，本地 Java Tool 应使用 ADK `FunctionTool`；
- 多实例生产部署仍需把单机内存中的能力快照迁移到 Redis，并接入正式密钥管理、数据库备份和审计保留策略。

`/memories` 提供 PostgreSQL 长期 Memory 的创建、结构化状态、确认、编辑和删除。
