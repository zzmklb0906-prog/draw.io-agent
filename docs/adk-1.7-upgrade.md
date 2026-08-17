# AI Draw.io Agent：ADK 1.7 架构、升级与交付指南

本文是项目后端架构、ADK 1.7 升级、YAML 装配、轻量监控、上下文治理和项目介绍的唯一权威文档。内容对应当前代码，不再描述升级前的 Spring AI 运行链路。

## 1. 项目定位

项目是一个配置驱动的多 Agent 创作平台，目前提供 Draw.io 流程图和 PPT 两类智能体。开发者通过 YAML 声明模型、Agent、工具组、Skills、Plugin 和工作流；应用启动时将配置装配为 Google ADK Agent 与 Runner；浏览器通过 HTTP 和 NDJSON 流进行对话，并实时渲染 Draw.io/PPT 结果。

一句话描述：

> 基于 Spring Boot 4 与 Google ADK Java 1.7 构建配置驱动式多 Agent 平台，使用原生 OpenAI-compatible 模型、MCP、Skills、工作流、Session Event 和 Plugin 生命周期，实现流式绘图、轻量监控与上下文治理。

## 2. 当前技术基线

- Java 17
- Spring Boot 4.0.2 / Spring Framework 7 / Tomcat 11
- Google ADK Java 1.7.0
- ADK `ChatCompletionsHttpClient`
- ADK `Runner`、`LlmAgent`、`SequentialAgent`、Plugin 和 Session
- ADK `McpToolset`
- ADK `ClassPathSkillSource`、`LocalSkillSource`、`SkillToolset`
- React、TypeScript、Vite、TanStack Query、Draw.io Embed
- Maven 多模块和 DDD 风格分层

Spring AI 已从生产代码、模型、MCP、Skills 和 Maven 依赖中移除。Spring Boot 只承担 Web、配置和依赖注入职责。

## 3. 总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│ React Frontend                                               │
│ 登录 / 历史会话 / Draw.io 画布 / 对话 / Model 设置 / 监控台 │
└───────────────────────────┬──────────────────────────────────┘
                            │ HTTP + application/x-ndjson
┌───────────────────────────▼──────────────────────────────────┐
│ trigger                                                      │
│ AgentServiceController / DTO / 流式连接生命周期              │
└───────────────────────────┬──────────────────────────────────┘
                            │ IChatService
┌───────────────────────────▼──────────────────────────────────┐
│ domain                                                       │
│ YAML 装配树 / Agent / Workflow / Runner / Plugin / Monitor   │
└──────────────┬──────────────────────────────┬────────────────┘
               │                              │
┌──────────────▼──────────────┐  ┌────────────▼────────────────┐
│ Google ADK Java 1.7         │  │ OpenAI-compatible Provider  │
│ Session/Event/Tool/Skill/MCP│  │ DeepSeek / compatible API   │
└─────────────────────────────┘  └─────────────────────────────┘
```

## 4. Maven 模块职责

| 模块 | 职责 |
|---|---|
| `ai-agent-scaffold-draw-io-app` | Spring Boot 启动、YAML、日志配置、资源与测试 |
| `ai-agent-scaffold-draw-io-api` | 对外服务接口和 DTO 契约 |
| `ai-agent-scaffold-draw-io-trigger` | HTTP Controller、NDJSON 输出和连接生命周期 |
| `ai-agent-scaffold-draw-io-domain` | Agent 装配、聊天服务、模型适配、Plugin 和监控 |
| `ai-agent-scaffold-draw-io-infrastructure` | 基础设施扩展边界 |
| `ai-agent-scaffold-draw-io-types` | 通用响应码、异常和公共类型 |
| `front` | React 前端工程 |

依赖方向以应用编排为中心：trigger 调用 domain 接口，domain 持有 ADK 运行模型，app 完成启动装配。它是实用型 DDD 分层，不是完全框架无关的纯领域模型。

## 5. 启动装配链

Spring 启动完成后，`AiAgentAutoConfig` 读取 Agent YAML，并按策略路由树依次执行：

```text
RootNode
  └─ AiApiNode
       └─ ChatModelNode
            └─ AgentNode
                 └─ AgentWorkflowNode
                      └─ RunnerNode
```

各节点职责：

1. `AiApiNode`：读取 API URL、Key 和路径。
2. `ChatModelNode`：创建 `OpenAiCompatibleLlm`，注册 MCP/Skill 工具组。
3. `AgentNode`：创建 `LlmAgent`，把 YAML 指定的工具组分配给具体 Agent。
4. `AgentWorkflowNode`：创建 sequential/parallel/loop 等组合工作流。
5. `RunnerNode`：创建 ADK `App + Runner`，配置 Plugin、Session 服务和事件压缩。
6. 最终按 `agentId` 注册 `AiAgentRegisterVO`，供聊天服务查询。

## 6. OpenAI-compatible 模型

`OpenAiCompatibleLlm extends BaseLlm` 内部使用 ADK 原生 `ChatCompletionsHttpClient`，支持 DeepSeek 和其他 OpenAI-compatible 服务。

推荐配置：

```yaml
ai-api:
  base-url: https://api.deepseek.com
  api-key: ${DEEPSEEK_API_KEY:}
  completions-path: /v1/chat/completions
chat-model:
  model: ${DEEPSEEK_MODEL:deepseek-chat}
```

以下两种路径均可使用：

```yaml
base-url: https://api.deepseek.com
completions-path: chat/completions
```

```yaml
base-url: https://api.deepseek.com
completions-path: /v1/chat/completions
```

第二种形式会归一化为 `baseUrl=https://api.deepseek.com/v1`，再由 ADK 客户端请求 `/chat/completions`。

前端可以按 Session 传递自定义 `baseUrl`、`apiKey`、`completionsPath` 和 `model`。`CustomConfigPlugin` 将覆盖项写入当前 `LlmRequest`，不会修改共享 Agent。

## 7. YAML Agent 与动态能力装配

工具不挂在共享 ChatModel 上。YAML 声明能力源，Agent 只声明允许运行时检索的能力域：

```yaml
chat-model:
  tool-skills-list:
    - name: drawio-skills
      type: resource
      path: agent/skills
      included-skills: [drawio]

agents:
  - name: agent_analyst
    description: 分析绘图需求
    output-key: analysis_result

  - name: agent_drawer
    description: 生成 Draw.io XML
    output-key: draft_diagram
    capability-groups:
      - drawio-skills

agent-workflows:
  - type: sequential
    name: sequential_draw_process
    sub-agents:
      - agent_analyst
      - agent_drawer

runner:
  agent-name: sequential_draw_process
  plugin-name-list:
    - customConfigPlugin
    - lightweightMonitoringPlugin
    - contextCompressionPlugin
```

这样 `agent_analyst` 没有 Broker，`agent_drawer` 只持有三个稳定元工具。模型先调用
`search_capabilities` 取得最多 16 个候选，再 `load_capability` 获取完整 Schema，最后用
`execute_capability` 执行。真实 Tool Schema 和 Skill 正文不会在未选中时进入上下文。

每次搜索生成 Invocation 级 `CapabilitySnapshot`：绑定 userId、agentName 与候选 ID。快照外能力不能执行；
快照信息写入 ADK Session State，Pause/Resume 或进程重启后可根据当前 Registry 恢复。监控台展示 SEARCH、
LOAD、EXECUTE 三类能力事件。

### 7.1 Skills

- classpath：`ClassPathSkillSource`
- 外部目录：`LocalSkillSource`

SkillToolset 只向模型提供目录查询与按需加载能力；Skill 正文及 references 不在启动时全部注入。
`included-skills` 可将每个 Agent 可见目录限制在最多 32 个 Skill。MCP 工具组默认必须配置
`included-tools` 白名单，且单组最多 64 个工具；`allow-all-tools: true` 是显式风险开关。面对数千工具时应按业务域建立
多个工具组，只把当前 Agent 阶段引用的组装配进 `LlmAgent`。

动态 Toolset 由 `GovernedToolset` 统一包装，提供调用超时、连续失败熔断和冷却恢复。默认不自动重试，
因为写操作 Tool 的盲目重试可能产生重复副作用；需要重试时应由具体 Tool 声明幂等语义后再开启。

### 7.2 动态模型路由

`ModelRoutingService` 使用输入长度、任务关键词和当前请求特征进行零 Token 规则路由。配置项为
`MODEL_FAST`、`MODEL_BALANCED`、`MODEL_REASONING`；前端显式选择的模型具有最高优先级。
- 工具集：`SkillToolset`

ADK 会把技能列表和加载能力暴露给模型。启动装配不等于工具执行；只有模型真正产生 function call 并读取 Skill 时，监控才记录 Tool 调用。

### 7.2 MCP

- SSE MCP：ADK `McpToolset + SseServerParameters`
- stdio MCP：ADK `McpToolset + StdioServerParameters`
- 本地 Java Tool：使用 ADK `FunctionTool`

升级前的 Spring AI `ToolCallback` 和 YAML `local` callback 兼容层已经删除。

### 7.3 Tool、MCP、Skill、Plugin 的区别

| 类型 | 作用 |
|---|---|
| Tool | 模型可调用的具体函数 |
| MCP | 通过标准协议从外部进程/服务发现和执行 Tool |
| Skill | 按需加载的领域说明、规范和相关资源 |
| Plugin | 介入 Runner、Agent、Model、Tool 和 Event 生命周期 |

## 8. 请求与流式运行链路

```text
POST /api/v1/create_session
  → ADK SessionService 创建 Session

POST /api/v1/chat_stream
  → ChatService.runAsync
  → SequentialAgent
  → agent_analyst 模型调用
  → Session stateDelta 写入 analysis_result
  → agent_drawer 模型/Skill 调用
  → Event 流
  → Controller 转换为 NDJSON
  → React ReadableStream 按行解析
  → Draw.io 渐进渲染
```

主接口使用 `application/x-ndjson`，不是标准 EventSource SSE。每行是一个完整 JSON 对象，前端必须按换行边界解析，不能假设一次网络 chunk 就是一条 Event。

## 9. ADK Event 字段

| 字段 | 含义 |
|---|---|
| `id` | 当前 Event 唯一 ID |
| `invocationId` | 一次完整 Runner 调用的链路 ID |
| `author` | 产生 Event 的 Agent |
| `content.parts[].text` | 本次文本或增量文本 |
| `actions.stateDelta` | Session State 增量 |
| `actions.artifactDelta` | Artifact 增量 |
| `requestedAuthConfigs` | Tool 请求的认证信息 |
| `requestedToolConfirmations` | Tool 请求的用户确认 |
| `functionCalls` | 模型发起的工具调用 |
| `functionResponses` | 工具执行结果 |
| `usageMetadata` | Provider 返回的 Token 使用量 |
| `partial` | 是否为流式片段 |
| `turnComplete` | 当前 Agent 轮次是否结束 |
| `timestamp` | Event 时间 |

流式模型可能按 token 或很短的字符串产生大量 `partial=true` Event。这是模型流到 ADK Event 的正常映射，不代表发生了大量 Agent 调用。

## 10. 轻量监控

监控完全运行在当前 JVM 内，不依赖 Prometheus、Grafana 或外部数据库。

### 10.1 监控范围

- Invocation 开始、完成、成功、异常和运行时长
- Agent 生命周期和耗时
- 模型调用次数、耗时和 Token
- Tool/MCP/Skill 调用 ID、Agent、耗时、状态和摘要
- Event 数量
- 上下文压缩前后 Token、策略和耗时
- 全局成功数、异常数、运行中数量和平均耗时

`runCompleted` 是幂等的。Runner 已完成后，晚到的 emitter completion/error 不会把状态重新改成 RUNNING 或 ERROR；未结束的 Agent/Tool 会被强制收口，避免前端计时器一直增长。

### 10.2 监控接口

```http
GET /api/v1/monitor/summary
GET /api/v1/monitor/invocations
GET /api/v1/monitor/invocations/{invocationId}
```

数据最多保留最近 200 个 Invocation，进程重启后清空。

### 10.3 Tool 计数规则

- “已注册”表示当前运行时已知的静态 Tool。
- `SkillToolset` 是动态工具集，启动时可能不出现在静态列表。
- Tool 次数只统计真实 `beforeToolCallback/afterToolCallback`。
- `functionCalls: []` 表示该条 Event 没有工具调用，不代表整个 Invocation 没调用工具。

## 11. 上下文治理

系统采用两层上下文保护。

### 11.1 ADK 原生摘要压缩

Runner 使用 `LlmEventSummarizer + EventsCompactionConfig`：

```yaml
runner:
  compaction-enabled: true
  compaction-token-threshold: 89600
  compaction-event-retention-size: 12
```

默认在约 128K 窗口的 70% 时调用模型摘要历史 Session Event，并保留最近 12 个事件。原生压缩事件以 `ADK_LLM_EVENT_SUMMARIZER` 进入监控。

### 11.2 请求级滑动窗口

`ContextCompressionPlugin` 是第二层保险：

- 0–70%：正常；ADK 根据事件阈值决定是否摘要。
- 85–90%：滑动窗口并注入结构化项目状态。
- 90–95%：激进裁剪旧文本和 Tool Result。
- 超过 95%：拒绝继续，要求新建或压缩 Session。

### 11.3 Draw.io 结构化状态

压缩时不只保存自然语言摘要，还写入：

```json
{
  "diagram_goal": "微信公众号扫码登录流程图",
  "diagram_type": "flowchart",
  "nodes": [],
  "edges": [],
  "constraints": {
    "layout": "preserve-current",
    "theme": "preserve-current"
  },
  "current_artifact_id": "diagram-xxxxxxxx"
}
```

大型 Tool/MCP 结果不长期保留在对话正文中；上下文保留摘要和 Artifact/项目状态引用。

## 12. Boot 4 与 ADK 1.7 迁移修复

- `javax.annotation.Resource` 改为 `jakarta.annotation.Resource`。
- 删除 `google-adk-spring-ai` 和 Spring AI MCP 自动配置。
- 删除 `MySpringAI`、`MyMessageConverter`、自定义 WebClient Filter。
- 删除 Community `SkillsTool` 和 Spring AI `ToolCallback` 适配器。
- 删除会与 ADK MCP SDK 冲突的 Spring AI MCP starter。
- 移除包含 Boot 3 类的旧 `xfg-wrench` uber JAR，在 domain 内保留所需策略树契约。
- 修复 ADK 1.7 不允许工作流重复挂载同名子 Agent 的 YAML 示例。
- 将 `InMemoryRunner` 快捷构造升级为 `App + Runner`，以支持原生事件压缩。
- 更新 Logback 1.5 的 conversion rule 和滚动策略。

## 13. 启动与验证

### 13.1 后端

```powershell
$env:DEEPSEEK_API_KEY='replace-me'
$env:DEEPSEEK_MODEL='deepseek-chat'
mvn clean package
java -jar ai-agent-scaffold-draw-io-app\target\ai-agent-scaffold-draw-io-app.jar
```

验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8091/api/v1/query_ai_agent_config_list
Invoke-RestMethod http://127.0.0.1:8091/api/v1/monitor/summary
```

### 13.2 前端

```powershell
cd front
npm install
npm run dev
```

访问 `http://127.0.0.1:5173`。演示登录是 `admin / admin`，只适合本地体验。

### 13.3 交付验证

```powershell
mvn test
mvn package
cd front
npm run build
```

当前自动化测试覆盖：

- DeepSeek/OpenAI-compatible URL 归一化
- Chat Completions Authorization 和请求体
- 文本响应与 usage token 映射
- ADK classpath Skill 发现和读取
- Invocation/Agent/Model/Tool/Token/压缩监控收口

## 14. 配置与数据安全

- API Key 通过环境变量或前端当前 Session 传递，不写入日志。
- 前端自定义 Key 只保存在页面内存。
- 监控仅保存 Tool 结果截断摘要，不保存完整敏感响应。
- 已出现在历史日志或提交中的 Key 必须在服务商控制台轮换。
- 生产环境应使用 Secret Manager、Vault 或部署平台密钥注入。

## 15. 当前边界与后续方向

- Session、Artifact、Memory 和监控仍为进程内实现，重启后丢失。
- 前端登录是演示实现，没有正式用户、Token 和权限系统。
- 本地 Java Tool 应直接使用 ADK `FunctionTool`；旧 Spring AI local callback 不再支持。
- 大规模部署应持久化 Session/Artifact，并增加租户隔离、限流和审计。
- 前端生产构建可继续按路由拆包，降低初始 bundle 大小。
- 可增加 Agent 自动化评测、模型质量对比与失败重放。

## 16. 简历与面试表述

### 推荐项目名称

基于 Google ADK 1.7 的配置驱动式多 Agent 绘图平台

### 简历项目描述

- 基于 Spring Boot 4 和 Google ADK Java 1.7 构建多 Agent 应用，通过 YAML 动态装配模型、Prompt、工具组、Skills、Plugin 和工作流。
- 基于 ADK `BaseLlm + ChatCompletionsHttpClient` 原生接入 DeepSeek/OpenAI-compatible 模型，移除 Spring AI 双框架适配和流式事件不一致问题。
- 将 Tool/MCP/Skill 从共享模型下沉到具体 Agent，解决多 Agent 重复读取 Skill，并通过 ADK function call/response 精确观测工具执行。
- 实现 Invocation、Agent、Model、Tool、Token 和上下文压缩的 JVM 轻量监控，无需额外部署 Prometheus/Grafana。
- 使用 `LlmEventSummarizer`、滑动窗口和 Draw.io 结构化项目状态治理长上下文，避免历史 Tool Result 持续占用模型窗口。
- 通过 NDJSON 将模型增量输出传递给 React 前端，渐进组装节点、连线和完整 Draw.io XML。

### 一分钟介绍

> 这是一个配置驱动的多 Agent 绘图平台。后端使用 Spring Boot 4 提供 Web 和配置能力，Google ADK 1.7 负责 Agent、工作流、Session、Event、MCP、Skills 和模型调用。项目通过 YAML 将分析、绘图等职责分给不同 Agent，并把 Skills 精确绑定到需要它的 Agent，避免重复调用。模型层使用 ADK 原生 Chat Completions 客户端接入 DeepSeek。运行过程中通过 Plugin 记录每个 Invocation、Agent、模型、Tool 和 Token，同时利用 ADK 原生摘要、滑动窗口和结构化图状态处理长上下文。前端通过 NDJSON 实时消费结果并更新 Draw.io 画布。

### 常见追问

**为什么使用 YAML？** 让 Agent 角色、Prompt、工具和工作流组合从 Java 代码中解耦，新增场景主要修改配置和 Skill。

**为什么移除 Spring AI？** ADK 1.7 已原生支持 Chat Completions、MCP 和 Skills；保留双框架会产生流式 Event、ToolCallback、usage 和依赖版本适配成本。

**为什么看到很多 Event？** 流式模型会把 token/chunk 转为多个 `partial=true` Event；应通过 `invocationId` 聚合，而不是把每条 Event 当成一次 Agent 调用。

**为什么 Tool 为 0？** 注册 Skill 不等于调用 Skill。只有模型真正发出 function call，ADK 才执行 Tool 并触发监控回调。

**是否是严格六边形架构？** 当前是 DDD 风格模块化结构，但 domain 仍直接依赖 ADK 与 Spring 容器；若要完全框架无关，需要再为 Runner、Model、Session 和 Tool 定义领域端口。

## 17. 推荐阅读顺序

1. 根目录 `README.md`
2. 本文第 3–8 节理解装配和请求链路
3. 第 9–11 节理解 Event、监控和上下文压缩
4. `agent-draw-io.yml` 与 `agent-ppt.yml`
5. `ChatModelNode`、`AgentNode`、`RunnerNode`
6. `OpenAiCompatibleLlm`、`LightweightMonitoringPlugin`、`ContextCompressionPlugin`
7. `front/README.md`
