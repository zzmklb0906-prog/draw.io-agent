# `wait-to-do.md` 实施规划与边界

## 1. 目标与明确不做的事项

本轮以 `wait-to-do.md` 为唯一范围来源。Agent Eval 暂停开发，也不把 Eval 测试作为本轮验收门禁。现有 Eval 代码和数据只保持兼容，不扩展、不删除。

实施目标按依赖顺序为：幂等性 → 恢复调度 → Tool/Skill 治理 → Workflow 观测 → 四层上下文 → 安全权限 → Artifact 版本 → 受约束动态 Subagent。

## 2. 领域边界

| 领域 | 负责内容 | 不负责内容 |
| --- | --- | --- |
| Identity | 用户、角色、JWT、Token 撤销 | Agent 执行状态 |
| Conversation | Session 对应的业务会话、消息顺序、最近窗口 | ADK Event journal |
| Workflow | Checkpoint、阶段转换、Pause/Resume、业务任务终态 | 模型内部 token 级恢复 |
| Runtime Observation | Invocation/Agent/Model/Tool 的事实记录和时间轴 | 决定业务状态 |
| Capability | Tool/Skill 注册、检索、版本快照、治理策略 | 保存业务会话 |
| Artifact | 大结果、图表/PPT、版本树、回退 | Tool 调度 |
| Context | 固定上下文、工作状态、近期窗口、Tool Result 引用 | 原始 Artifact 存储 |
| Orchestration | 动态 Subagent Spec、DAG、预算、父子运行 | 任意生成 Java 类 |

## 3. 关键不变量

1. 一个幂等键只能对应同一用户、同一操作和同一请求摘要；同键不同请求必须冲突。
2. 一个 Session 同时最多有一个可写 Invocation；并发保护不能只依赖单 JVM 内存。
3. Checkpoint revision 是业务状态 CAS；幂等键解决网络重发，两者不能互相替代。
4. Tool 的 `tool_call_id` 只在 Invocation 内唯一；平台幂等键需要跨重试保持稳定。
5. 恢复调度器只有获得数据库租约后才能接管任务；心跳有效的其他实例任务不能被回收。
6. 不从 LLM token 中间恢复，只从稳定 Checkpoint/Tool 边界恢复。
7. Tool 大结果保存 Artifact；上下文只保留摘要、哈希和 Artifact ID。
8. Workflow 是业务任务，Invocation 是一次物理执行；多次审核/恢复必须归属同一个 Workflow。

## 4. 分阶段实施

### Phase A：统一幂等性

- 新增 `idempotency_record`：所有者、作用域、幂等键、请求哈希、状态、资源 ID、响应快照、租约与过期时间。
- API 接收 `idempotencyKey`：创建 Session、消息/审批提交和 Invocation 重试。
- Conversation Message 增加幂等键唯一约束。
- Checkpoint 决策同时校验 revision 与幂等键。
- Artifact 使用确定性幂等键返回既有 Artifact。
- Tool 执行记录平台幂等键，重试复用原执行记录并新增 attempt。

影响：需要前端在用户意图级生成一次 UUID，并在网络重试时复用，不能每次 HTTP 重试重新生成。

### Phase B：恢复调度器

- 新增实例注册、任务租约、心跳和恢复策略字段。
- 启动扫描只处理租约过期的 `RUNNING`。
- 有稳定 Checkpoint 的任务进入恢复队列；无稳定边界的标记 `INTERRUPTED`。
- Invocation、Tool 分别配置运行超时与心跳超时。
- 调度接管必须携带幂等键，防止重复拉起。

影响：现有启动时直接把所有 RUNNING 标记 FAILED 的逻辑需迁移到调度器，不能并存。

### Phase C：Capability 与 Tool 治理

- 每次 Invocation 保存 Agent/Prompt/模型/Skill/Tool Schema 的不可变版本快照。
- Tool Policy 包含超时、重试、退避、熔断、并发、风险、幂等、并行许可和人工审批。
- 检索记录补充期望/误选反馈与 Skill 对结果影响证据。

### Phase D：Workflow 级观测

- 新增 Workflow 详情接口与页面。
- 聚合 Invocation、Checkpoint、Artifact、Tool、Token、错误与重试。
- 单独计算模型、Tool、用户审批等待、队列等待和墙钟耗时。

### Phase E：上下文、安全、Artifact 与 Subagent

- 四层上下文分别版本化；固定层不参与普通摘要。
- RBAC、工作区隔离、Tool Policy、审计、脱敏和 Token 撤销。
- Artifact Revision/父版本/分支/回退。
- 模型只生成受 Schema 约束的 Subagent Spec；平台校验 DAG、深度、并发、权限和预算后创建 ADK Agent。

## 5. 验收证据

- 数据库唯一约束和并发测试证明重复请求只产生一份资源。
- 重启/双实例测试证明有效租约不被接管、过期租约只被一个实例接管。
- 监控详情能追溯每次执行所用完整版本快照。
- Workflow 页面分项耗时之和可解释，且审批等待不计入计算耗时。
- 压缩前后结构化工作状态与 Artifact 引用保持一致。
- 权限测试证明跨用户/跨工作区资源不可访问。
- Artifact 回退产生新 Revision，不覆盖历史内容。

## 6. 风险控制

- 当前工作树包含大量已有改动，所有迁移只追加，不重写既有 Flyway 文件。
- 先保持单实例行为兼容，再启用租约调度；通过配置开关灰度。
- 前端和后端同时兼容暂未提供幂等键的旧客户端，但新 UI 必须发送。
- Tool/Skill 治理先观测后强制，避免策略上线直接阻断已有 Agent。

## 7. 实施完成审计（2026-08-17）

> Agent Eval 依照 `wait-to-do.md` 暂停；既有代码和迁移仅保持兼容，不计入本轮完成范围。

| 清单项 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| Session / 消息 / Checkpoint 幂等 | `IdempotencyService`、`idempotency_record`、API 请求哈希与资源回放；同键不同请求返回冲突 | 已完成 |
| Tool / Artifact / Invocation 重试幂等 | Tool 使用稳定请求 ID + 能力 + 参数摘要；Artifact 有唯一键；Invocation 保存 `request_id`、attempt 与 retry-of | 已完成 |
| 恢复调度 | 实例心跳、Invocation 租约、遗留任务扫描、恢复队列、超时终止、Tool deadline 回收；晚到回调不得覆盖恢复终态 | 已完成 |
| 能力版本与检索治理 | Invocation 保存 Agent/Prompt/模型版本；候选与执行保存 Skill/Schema/内容版本、分数、选择、反馈与 Artifact 来源 | 已完成 |
| Tool 运行治理 | 独立超时、重试、指数退避、熔断、并发/并行策略、风险等级、幂等、结果限额/脱敏/Artifact 外置 | 已完成 |
| 高风险 Tool 审批 | ADK `adk_request_confirmation` 映射为持久化 Checkpoint，前端批准/拒绝后用 FunctionResponse 恢复；决策写审计日志 | 已完成 |
| Workflow 观测 | Workflow 聚合所有 Invocation、状态迁移、模型/Tool/人工/队列耗时、Tokens、费用、重试、Artifact 与最终结果 | 已完成 |
| 四层上下文 | 固定 Prompt/Tool Schema 不进入消息压缩且有版本快照；结构化工作状态、近期窗口、Tool 摘要+Artifact 引用分别处理 | 已完成 |
| 安全与权限 | JWT/Refresh rotation、Redis Token 黑名单与登录限流、Workspace RBAC、资源隔离、审计、脱敏；请求 API Key 不落库且请求结束清理 | 已完成 |
| Artifact 版本 | Revision/父版本/谱系/分支、Diff、回退生成新版本、Invocation 归因和并发唯一约束 | 已完成 |
| 受约束动态 Subagent | 模板化 ADK LlmAgent、DAG 前置依赖、显式父 Run、深度/并发/任务/Token/超时/权限预算、失败传播与部分结果回收 | 已完成 |

### 验证记录

- Maven 七模块编译：`mvn -pl ai-agent-scaffold-draw-io-app -am -DskipTests compile` 通过。
- 前端类型检查：`npm run typecheck` 通过。
- 在独立 PostgreSQL 数据库按数字顺序重放 `V1`–`V20` 成功，共创建 38 张业务表。
- `git diff --check` 无空白错误；现有换行符提示不影响构建。

### 尚需人工运行验证的场景

下列项目属于部署环境验收，不是未实现功能：双实例强制退出后的唯一接管、高风险真实 Tool 的批准/拒绝、95% 上下文预算保护、跨用户/跨 Workspace 越权、Artifact 分支并发、Subagent 父任务超时取消。自动化 Agent Eval 仍按用户要求暂停。
