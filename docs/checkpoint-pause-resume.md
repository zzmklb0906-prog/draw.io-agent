# 基于 Checkpoint 的持久化 Pause/Resume

## 1. 实现目标

本项目现在把“关闭 SSE”与“暂停工作流”区分开：

- SSE 只是传输通道；断开连接不再被当作工作流状态。
- Checkpoint 是可持久化、带版本号的业务状态。
- ADK Session 的 state、Event journal、Checkpoint 与 revision 默认写入 PostgreSQL，应用重启后仍可加载。
- 人工审核发生在稳定阶段边界：`ANALYSIS -> APPROVAL -> DRAWING`。
- ADK `ResumabilityConfig.resumable=true` 已启用，用于 ADK 内部工作流与长运行 Tool 的恢复语义。

这里不会尝试从某个 LLM token 中间继续。模型调用不是可移植事务；如果绘图阶段被暂停，恢复时会从最近的稳定阶段重新进入绘图阶段。这是可重复、可审计的恢复语义。

## 2. 状态机

```text
RUNNING/ANALYSIS
    -> WAITING_APPROVAL/APPROVAL
        -> RUNNING/DRAWING        (APPROVE)
        -> RUNNING/ANALYSIS       (REVISE)
    -> PAUSED                     (用户停止)
    -> COMPLETED | FAILED | CANCELLED
```

每次状态变化都会增加 `revision`。恢复请求必须提交前端最后看到的 revision；旧页面、重复点击和并发审批会收到 `CHECKPOINT_CONFLICT`，防止同一工作流重复执行。

## 3. 持久化内容

默认存储：

PostgreSQL 中的 `adk_session`、`adk_session_event`、`workflow_checkpoint` 表。Redis 只保存 Session 执行租约与可重建的短期缓存，不是 Checkpoint 或上下文摘要的事实源。

本地文件实现仍可作为开发兼容模式：

```yaml
ai:
  agent:
    persistence:
      mode: file
      root: E:/data/draw-io-agent
```

PostgreSQL 模式下，Checkpoint 保存 agent/user/session、阶段、状态、原始提示、审核 JSON、invocationId、错误信息与 revision；更新使用 revision 条件更新实现 CAS，避免重复恢复。Redis `SET NX` 租约阻止同一用户 Session 被并发执行。

## 4. HTTP 协议

首次执行仍调用：

```http
POST /api/v1/chat_stream
Content-Type: application/json

{"agentId":"300000","userId":"admin","sessionId":"...","message":"画登录流程图"}
```

流首先返回 checkpoint 帧，审核时返回带 checkpoint 的 approval 帧：

```json
{"phase":"thinking","chunk":{"type":"checkpoint","checkpointId":"...","revision":1,"status":"RUNNING"}}
{"phase":"analyzing","chunk":{"type":"approval","checkpointId":"...","revision":2,"checkpointStatus":"WAITING_APPROVAL"}}
```

批准并恢复：

```http
POST /api/v1/chat_stream

{
  "agentId":"300000",
  "userId":"admin",
  "sessionId":"...",
  "message":"",
  "checkpointId":"...",
  "checkpointRevision":2,
  "checkpointDecision":"APPROVE"
}
```

修改方案时将 decision 改成 `REVISE`，并把修改意见放到 message。

管理接口：

- `GET /api/v1/workflows/{checkpointId}`：查询持久化状态。
- `POST /api/v1/workflows/{checkpointId}/pause`：持久化暂停。
- `POST /api/v1/workflows/{checkpointId}/cancel`：不可恢复地取消。

## 5. 前端行为

- 审核卡片保存后端返回的 checkpointId 和 revision。
- “确认并开始绘图”不再构造普通聊天文本，而是发出带版本号的恢复请求。
- “修改方案”使用 `REVISE` 从分析阶段恢复。
- “停止”先中止当前流，再调用 pause 接口；返回的新 revision 会同步回审核卡片。
- 浏览器历史仍负责 UI 快照，但后端 Session/Checkpoint 已不再依赖浏览器存活。

## 6. 边界与后续扩展

当前 PostgreSQL 实现支持重启恢复和数据库级 revision CAS；Redis Session 租约支持多实例的并发互斥。生产环境仍建议增加租约续期、数据库备份和过期 Checkpoint 清理任务。

如果后续需要在 Tool 执行前人工确认，应直接映射 ADK Event 的 `requestedToolConfirmations`，把 pending tool call ID 写入 Checkpoint；恢复时提交确认结果。阶段审批和 Tool 审批是两个层级，不应混成普通提示词。
