# 动态 Subagent 与统一调用链

## 1. 运行模型

通用 Agent 不再只能执行 YAML 中固定的 `subAgents`。它拥有三个稳定控制面工具：

- `list_subagent_templates`：从 PostgreSQL `agent_template` 查询允许使用的角色模板；
- `spawn_subagent`：在当前 Invocation 内运行时构造新的 ADK `LlmAgent`；
- `await_subagent`：等待并取得子任务结果。

模型只能选择已启用、带版本的模板，不能提交 Java 类、任意系统提示或 Shell 命令。每个 Invocation 最多创建 8 个动态 Subagent；子 Agent 受模板的 `max_steps` 和 `timeout_seconds` 约束，也不会继承 `spawn_subagent`，因此不会无限递归创建。

`spawn_subagent` 立即返回 `taskId`。根 Agent 可以先创建多个独立任务，再调用 `await_subagent` 汇总，实现并发执行。任务状态和结果写入 `dynamic_subagent_task`。

## 2. 显式父子 Run 上下文

`LightweightMonitoringPlugin` 在 ADK `beforeAgentCallback` 中读取：

- `BaseAgent.parentAgent()`：静态 Sequential / Parallel / Loop 的结构父节点；
- `CallbackContext.branch()`：区分并行分支；
- `monitor.forcedParentRunId.{branch}`：动态 Subagent 在派生 `InvocationContext` 时写入的父 Run。

`LightweightMonitorService` 为每次进入 Agent 生成独立 `runId`，并把 `runId + parentRunId + branch` 直接交给仓储。仓储不再查询“当前正在运行的某个 Agent”来推断父节点。因此，同名 Agent 的循环执行、并行分支和深层嵌套都会产生独立的 `agent_run`。

## 3. 持久化

Flyway V4 增加：

- `agent_run.branch_path / run_kind / template_key`；
- `agent_template`；
- `dynamic_subagent_task`。

每次 Agent、Model、Tool 都带准确的 `agent_run_id`。Agent 完成时，Tokens 和模型调用次数从该 Run 的 `model_call` 聚合，避免同名 Loop Run 相互污染。

## 4. 统一瀑布图

Invocation 详情接口返回 `waterfall`，统一投影：

- Agent Run；
- Model Call；
- Tool Call。

前端按 Invocation 的最早开始时间计算相对偏移和宽度，以同一时间轴展示父子层级、并发重叠、状态、耗时与 Tokens。原有分区表仍保留，用于查看参数摘要、Tool 尝试、错误和上下文压缩。

## 5. 测试

启动 PostgreSQL 和 Redis 后运行：

```powershell
mvn test
cd front
npm test -- --run
npm run build
```

登录后选择“通用任务智能体”，可使用如下提示验证动态执行：

```text
请把以下任务拆成两个可并行子任务：
1. 分析当前项目 Checkpoint/Pause/Resume 状态机；
2. 独立审查其并发与持久化风险。
请使用动态 Subagent，最后合并证据和建议。
```

监控页面应出现 `spawn_subagent` / `await_subagent` Tool、`dynamic_*` Agent Run、动态任务记录和重叠的瀑布条。
