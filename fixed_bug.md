# Agent Platform 生产级故障复盘与修复记录 (fixed_bug.md)

本文档记录了 Agent 平台在重构与高并发测试过程中排查并彻底解决的 6 大关键 Bug。提供事故现象、根因分析、解决方案与源码修复点，方便后续复习与查阅。

---

## 目录
1. [Bug 1: 阿里百炼 MaaS 异构 Multi-Provider API Key 独立映射缺失](#bug-1-阿里百炼-maas-异构-multi-provider-api-key-独立映射缺失)
2. [Bug 2: Flyway 数据库迁移脚本 Checksum Mismatch 校验中断](#bug-2-flyway-数据库迁移脚本-checksum-mismatch-校验中断)
3. [Bug 3: NDJSON 流式接口发生异常时的 HttpMessageNotWritableException 崩溃](#bug-3-ndjson-流式接口发生异常时的-httpmessagenotwritableexception-崩溃)
4. [Bug 4: 大模型工具调用幻觉抛出裸露 Exception 中断 Self-Correction 闭环](#bug-4-大模型工具调用幻觉抛出裸露-exception-中断-self-correction-闭环)
5. [Bug 5: 监控台 Workflow 拓扑被记录为“未知 Agent”](#bug-5-监控台-workflow-拓扑被记录为未知-agent)
6. [Bug 6: 工具必需参数校验裸抛 Exception 阻断 LLM 自恢复闭环](#bug-6-工具必需参数校验裸抛-exception-阻断-llm-自恢复闭环)
7. [架构设计思考与深度复盘 (Architectural Trade-off & Insights)](#7-架构设计思考与深度复盘-architectural-trade-off--insights)

---

### Bug 1: 阿里百炼 MaaS 异构 Multi-Provider API Key 独立映射缺失

#### 1. 事故现象
系统在大模型路由策略（Strategy Pattern）将请求分流至不同模型系列（如 Qwen、DeepSeek、GLM、Kimi）时，频繁触发 `401 Unauthorized` 认证失败或连接断开异常。

#### 2. 根因分析
在阿里云百炼 MaaS 平台中，尽管不同模型的 OpenAI 兼容 Endpoint URL 相同（均形如 `https://llm-ur2u3ht72sj00jc8.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`），但 **Qwen、DeepSeek、GLM、Kimi 属于不同的服务授权包，各自拥有独立的 API Key**。先前系统全局只配置了单一默认 API Key，导致路由到异构厂商模型时鉴权失败。

#### 3. 解决方案
设计并实现了 **多厂商提供商注册中心 (Model Provider Registry)** 机制：
- 定义 [ModelProviderProperties.java](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-domain/src/main/java/cn/bugstack/ai/domain/agent/service/llm/provider/ModelProviderProperties.java)，支持纯配置驱动解耦；
- 实现 [ModelProviderRegistryService.java](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-domain/src/main/java/cn/bugstack/ai/domain/agent/service/llm/provider/ModelProviderRegistryService.java)，在拦截器 [CustomConfigPlugin.java](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/matter/plugin/CustomConfigPlugin.java) 中根据路由决策模型自动动态注入匹配的 `BaseUrl` 与 `ApiKey`。

---

### Bug 2: Flyway 数据库迁移脚本 Checksum Mismatch 校验中断

#### 1. 事故现象
应用启动时抛出 Flyway 校验失败异常：
```log
org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'agentPlatformFlyway': 
Validate failed: Migrations have failed validation. Migration checksum mismatch for migration version 21.
-> Applied to database : 1009803661
-> Resolved locally    : 1070963486
```

#### 2. 根因分析
Flyway 对所有已执行过的 Migration SQL 具有严格的 MD5 校验机制。开发过程中对已运行过的 `V21__add_more_users.sql` 文件进行了直接改动（添加了 Workspace 初始化语句），导致本地文件 MD5 与数据库历史记录不匹配，出于数据安全保护触发校验拒绝。

#### 3. 解决方案
- 遵循 Flyway 增量不可变规范，将 [V21__add_more_users.sql](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-app/src/main/resources/db/migration/V21__add_more_users.sql) 还原为初始版本（恢复 Checksum `1009803661`）；
- 新增独立的增量迁移脚本 [V22__auto_init_user_workspaces.sql](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-app/src/main/resources/db/migration/V22__auto_init_user_workspaces.sql) 补全 Workspace 数据；
- 清理根目录下非标准的 `scripts/` 临时目录，统一由 Flyway 标准路径托管。

---

### Bug 3: NDJSON 流式接口发生异常时的 HttpMessageNotWritableException 崩溃

#### 1. 事故现象
当 `/api/v1/conversations/messages` 流式接口在对话中途抛出异常时，后端控制台打印：
```log
ERROR ApiExceptionHandler - 未处理的 API 异常
org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap] with preset Content-Type 'application/x-ndjson'
```
随后引发 Servlet 500 严重错误。

#### 2. 根因分析
流式接口预设 HTTP 请求头 `Content-Type: application/x-ndjson`。当系统抛出异常触发全局 `@RestControllerAdvice` (`ApiExceptionHandler`) 时，`ApiExceptionHandler` 尝试返回 `ResponseEntity<Response<Void>>` 对象。但 Spring Web MVC 的 `HttpMessageConverter` 注册列表中没有专门针对 `application/x-ndjson` 类型的 Java 对象转换器，引发序列化失败并二次砸崩异常处理链路。

#### 3. 解决方案
在 [ApiExceptionHandler.java](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-trigger/src/main/java/cn/bugstack/ai/trigger/http/ApiExceptionHandler.java) 中，为所有异常 Handler 显式指定返回 `MediaType.APPLICATION_JSON`：
```java
return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(...);
```
保证无论前端预设何种流式 Content-Type，异常发生时均能 100% 成功以标准的 JSON 格式返回错误响应。

---

### Bug 4: 大模型工具调用幻觉抛出裸露 Exception 中断 Self-Correction 闭环

#### 1. 事故现象
在模型路由分流至轻量模型（如 `qwen3.7-flash`）时，大模型产生工具调用幻觉，传入伪造参数 `{"snapshotId": "none", "capabilityId": "none"}`，导致系统中断：
```log
java.lang.IllegalArgumentException: 能力快照不存在或已过期，请重新搜索
```

#### 2. 根因分析
在 Agentic 架构中，大模型的自我纠错 (Self-Correction) 完全依赖于 **Tool 返回包含错误信息的 Result Map 文本**。先前的 `ExecuteTool.runAsync` 在发现快照无效时，直接抛出了原生的 Java `IllegalArgumentException` 运行时异常。裸露异常砸崩了后台线程与连接，导致大模型无法接收到错误反馈，Self-Correction 纠错反思循环失效。

#### 3. 解决方案
在 [CapabilityBrokerToolset.java](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-domain/src/main/java/cn/bugstack/ai/domain/agent/service/capability/CapabilityBrokerToolset.java) 中加入假参数防范与安全 Result 返回：
```java
if ("none".equalsIgnoreCase(snapshotId) || "none".equalsIgnoreCase(capabilityId) || snapshotId.isBlank() || capabilityId.isBlank()) {
    return Single.just(Map.of("status", "ERROR", "error", "未提供有效的 snapshotId 与 capabilityId。若无需使用扩展能力，请直接回答；若需使用请先调用 search_capabilities 检索。"));
}
try {
    descriptor = registry.load(snapshotId, capabilityId, context);
} catch (Exception e) {
    return Single.just(Map.of("status", "ERROR", "error", "能力快照不存在或已过期，请重新调用 search_capabilities 搜索。"));
}
```
通过返回结构化的错误 Result，让大模型收到反馈文本并触发内置的反思与自纠错循环，避免流程被异常硬性砸崩。

---

### Bug 5: 监控台 Workflow 拓扑被记录为“未知 Agent”

#### 1. 事故现象
前端监控仪表盘 (`MonitorPage.tsx`) 的 Workflow 列表与 Invocation 树形图上，频繁出现 `未知 Agent` 分组。

#### 2. 根因分析
在监控服务 [LightweightMonitorService.java](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-domain/src/main/java/cn/bugstack/ai/domain/agent/service/monitor/LightweightMonitorService.java) 中，模型路由切面拦截器早于 Agent 引擎 `runStarted` 触发，向监控服务登记了 `modelRouted` 事件，监控服务自动为其建立了初始化占位记录（`rootAgent` 与 `workflowName` 默认为 `"unknown"`）。旧代码中 `runStarted` 采用了死板的 `computeIfAbsent`，未能更新已存在的 `"unknown"` 占位 Record。

#### 3. 解决方案
重构 `LightweightMonitorService.runStarted` 方法与 `InvocationRecord` 内部类：
- 当后到的真实 `runStarted` 被触发时，自动检测并覆盖更新现有 `unknown` 记录的 `rootAgent`、`sessionId`、`userId` 与 `workflowName`；
- 保证监控端渲染出完整准确的 Agent 拓扑关系链。

---

### Bug 6: 工具必需参数校验裸抛 Exception 阻断 LLM 自恢复闭环

#### 1. 事故现象
即使在修复快照不存在的场景后，轻量模型（如 `qwen3.7-flash`）发生工具混淆未传 `snapshotId` 参数时，后台控制台仍然抛出未捕获异常：
```log
java.lang.IllegalArgumentException: snapshotId is required
```
引发 HTTP 500 流式对话中断。

#### 2. 根因分析
在 `CapabilityBrokerToolset.java` 的辅助函数中，定义了硬性校验方法 `required(args, "snapshotId")`。当大模型产生参数漏传（例如把 `search_capabilities` 的参数结构误传给 `execute_capability`）时，`required` 方法在 `try-catch` 逻辑前直接抛出了裸露的 `IllegalArgumentException`，绕过了 Tool 的 Result 封装，导致大模型无法拿到报错 Result 反馈进行自纠错。

#### 3. 解决方案
- 将 `LoadTool` 与 `ExecuteTool` 中的参数硬校验替换为安全的 `optString(args, "snapshotId")`；
- 当 `snapshotId` 或 `capabilityId` 为空时，安全封装并返回友好的结构化 Tool Result，明确告知大模型缺失的必填参数与替代策略；
- 赋予 LLM 完整的 Self-Correction 纠错引导。

---

## 7. 架构设计思考与深度复盘 (Architectural Trade-off & Insights)

### 💡 1. 动态能力代理架构 (Dynamic Capability Broker) 的利弊权衡 (Trade-Off)

#### 收益（Tokens 节省 80%+ 与无限可扩展性）
传统的 Agent 架构在初始化时会将系统中所有 Tool/Skill/MCP（可能多达 50+ 个）的 JSON Schema 全部写进 Prompt 的顶部。随着能力集的扩展，**光工具描述就会占用 4,000~10,000 个 Input Tokens**。
采用 `search_capabilities` 动态能力封装后，入口仅向 LLM 暴露 3 个元工具 (`Meta Tools`)，全局 Token 消耗急剧下降 **80%~90%**，且系统可无限挂载海量 MCP 工具。

#### 门槛（对 LLM 两阶段模式识别与动作链的要求）
动态能力代理依赖大模型具备 **“两阶段动作链 (Two-Stage Action Pattern)”**：
- **阶段 1 (能力检索)**：调用 `search_capabilities` -> 产生快照 ID `snapshotId`；
- **阶段 2 (能力执行)**：提取 `snapshotId` + `capabilityId` -> 调用 `execute_capability`。

**当模型推理能力较低时（如 Flash 轻量快模型，或极简 Prompt 被自动路由到低复杂度档位时）**：
- 模型容易跳过阶段 1（未搜索），直接硬性触发阶段 2（调用执行）；
- 或者产生工具混淆，错把阶段 1 的搜索参数结构（`query`/`limit`）套给阶段 2 的执行工具。
- **结论**：动态能力注册架构越高级，对模型 Tool Calling 的指令遵从度 (Instruction Following) 要求越高。必须具备防范与自我纠错闭环机制。

---

### 🎯 2. Agent 错误反馈提示词 (Tool Error Guidance) 的设计原则

#### ❌ 传统否定命令句的缺陷
若 Tool 执行失败时直接返回否定命令句（如 *"直接回答或绘图请勿调用 execute_capability"*）：
1. 否定句易在 LLM Prompt 中造成**负向心理暗示**；
2. 缺乏 Actionable Guidance（可操作的步骤指引），轻量模型可能不知所措。

#### ✅ 优化后的结构化正向指引规范
优秀的 Tool Error Result 应当具备 **结构化、正向引导、明确步骤指引** 特征：
```json
{
  "status": "INVALID_CALL",
  "error": "执行动态能力失败：未接收到有效的 'snapshotId' 或 'capabilityId' 参数。",
  "guidance": "请按以下步骤操作：1. 如需检索外部扩展能力，请先调用 'search_capabilities(query=...)' 获取快照 ID；2. 如需直接生成 Draw.io 图表或回答用户问题，请勿调用此工具，直接在回答中输出内容即可。"
}
```
通过向模型提供明确的可执行步骤（Actionable Steps），使轻量模型在发生参数缺失或工具误触发时，**能够精准看懂自己的报错并成功触发内置的 Self-Correction（自我纠错）机制完成自动恢复**！

---

### 🚀 3. 通用性与自检索 Skill 的冲突及柔性正向引导 (Soft Incentive Prompting)

#### 现象：模型具备先验知识时“懒于检索”
在测试中发现：即使工具链完全可用，模型直接生成了 8,469 字符的 Draw.io XML（`COMPLETED`），但并未主动调用 `search_capabilities`。

#### 原因复盘
1. **模型内置先验知识 (Zero-Shot Capability)**：预训练权重中包含大量的 XML/Mermaid/Draw.io 格式，模型判断自身内置知识足以直接完成任务；
2. **最小 Token 消耗原则**：模型会选择路径最短的解决方式（避免不必要的 Tool Call 折返）；
3. **分流到 L1 快模型**：Flash 轻量模型倾向于快速产出结果而非开展长链条 ReAct 思考。

#### 通用与主动的黄金平衡解法：柔性正向引导 (Soft Incentive Prompting)
- **硬编码命令（非通用）**：`"你必须先调用 search_capabilities"` -> 太死板！处理简单对话时会导致无效搜索。
- **完全不引导（易偷懒）**：模型依靠先验知识裸奔，完全忽略扩展库。
- **通用柔性正向引导（推荐解法）**：在系统提示词中加入倾向性引导，既保持 100% 通用性，又赋予 LLM 主动搜索动机：
```yaml
instruction: |
  【能力增强建议】：
  在开始处理用户的复杂绘图或架构设计任务前，强烈建议你先调用 `search_capabilities` 搜索系统中是否存在可增强本次任务的特定领域技能（Skills）、绘图样式指南（Style Guides）或专业 MCP 工具。
  若检索到相关能力，请优先加载并遵循其规范，以输出更专业、高质量的结果。
```

#### Agent 三大工具挂载架构对比

| 架构流派 | 做法 | 优势 | 劣势/弊端 | 适用场景 |
| :--- | :--- | :--- | :--- | :--- |
| **1. 全量静态硬编码 (Hardcoded Tools)** | 把所有 50 个工具 Schema 全部硬塞进 Prompt 顶部 | 模型眼睛能直接看见所有工具，**触发率 100% 极高** | Tokens 爆表（每次消耗几千 Tokens），工具上限仅 10~20 个 | 专属单功能小 Agent |
| **2. 强制硬编码工作流 (Hard-coded Pipeline)** | 用代码强行规定：“第1步必须搜索 -> 第2步必须加载 -> 第3步生成” | 链路 100% 确定，绝不出错 | **失去 Agent 的智能与通用性**，沦为传统 IF-ELSE 脚本 | 自动化流水线任务 |
| **3. 动态元能力代理 (Dynamic Capability Broker)** *(当前架构)* | 入口仅暴露 `search_capabilities` 等 3 个元工具 | **Token 极其节省 (降 80%+)**，支持无限挂载成千上万 MCP | **极度依赖模型的自主 Planning 能力与 Prompt 引导强度**（低端模型易“懒惰”不出查） | **企业级通用 Agent 平台** |

---

## 8. 会话产物持久化 SQL 占位符错位异常 (DataIntegrityViolationException)

### 问题现象
智能体完成绘图任务后，控制台抛出以下异常：
```log
ERROR AgentServiceController - Agent 已完成但会话产物持久化失败
org.springframework.dao.DataIntegrityViolationException: PreparedStatementCallback; SQL [...]; 未设定参数值 17 的内容。
```

### 根因分析
在 `JdbcArtifactRepository.java` 的 `save` 与 `branch` 方法中，`INSERT INTO artifact(...) VALUES (...)` 的硬编码 SQL 语句里，`storage_type` 列的固定值 `'DATABASE'` 被误插到了问号占位符的中间位置，导致 SQL 的 `?` 占位符总数（17个）与 Java 代码传入的参数数组（16个）**错位不匹配**，进而引发 JDBC 驱动抛出 `未设定参数值 17` 异常。

### 修复方案
在 [JdbcArtifactRepository.java](file:///E:/java/back/study/draw-io-agent/ai-agent-scaffold-draw-io-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/persistence/JdbcArtifactRepository.java) 中矫正了 `insert into artifact` 的 VALUES 列名与 `?` 占位符顺序，使其与 16 个 Java 变长参数一一精准对应。

---
*文档更新时间：2026-08-18*
