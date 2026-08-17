# 1. Agent Eval 平台
这个Agent Eval 平台暂时停止开发，自动化测试请先暂停

# 2. 幂等性
 需要给以下操作增加明确的幂等键：

  - 创建 Session。
  - 提交消息。
  - 审批 Checkpoint。
  - Tool 执行。
  - Artifact 保存。
  - 重试 Invocation。

  否则前端重复点击、网络重发可能产生重复消息、重复绘图或重复 Tool 调用。

#  3. 任务恢复调度器

  现在有持久化状态，但还应增加后台恢复机制：

  - 启动时扫描遗留 RUNNING 任务。
  - 区分“进程崩溃”与“仍在其他实例运行”。
  - 可恢复任务重新排队。
  - 不可恢复任务标记 INTERRUPTED。
  - 超时任务自动失败或暂停。
  - Tool 长时间无心跳时自动回收。

# 4. 其余任务
  ## P1：Tool 与 Skill 治理

  1. 能力版本管理

  目前应进一步记录：

  - Skill 版本。
  - Tool Schema 版本。
  - Prompt 版本。
  - Agent 配置版本。
  - 模型版本。
  - 每个 Invocation 实际使用的版本快照。

  这样才能回答：“为什么昨天效果正常，今天退化了？”

  2. 动态能力检索质量

  应该监控：

  - 搜索 Query。
  - 候选能力及分数。
  - 最终选中的能力。
  - 明明需要但未选择的能力。
  - 错误选择的能力。
  - Skill 加载后是否真正影响最终输出。

  3. Tool 运行治理

  还需要补充：

  - 每个 Tool 独立超时。
  - 最大重试次数。
  - 指数退避。
  - 熔断。
  - 并发限制。
  - 风险分级。
  - 幂等声明。
  - 是否允许并行。
  - 是否需要人工批准。
  - Tool Result 大小限制和敏感信息脱敏。

  ## P1：统一 Workflow 观测

  监控台现在仍以 Invocation 详情为主，下一步可以增加真正的 Workflow 详情页：

  drawIoAgent Workflow
  ├── Invocation 1 · ANALYSIS
  │   └── agent_analyst
  ├── Invocation 2 · REVISE
  │   └── agent_analyst
  └── Invocation 3 · APPROVE
      └── agent_drawer
          ├── search_capabilities
          ├── load_capability
          └── execute_capability

  Workflow 页面应汇总：

  - 总耗时和实际计算耗时。
  - 用户等待审批时间。
  - 所有 Invocation。
  - 总 Tokens 与费用。
  - Tool 成功率。
  - Checkpoint 状态变化。
  - Artifact 版本。
  - 最终结果。
  - 错误和重试记录。

  尤其要区分：

  - 模型运行时间。
  - Tool 运行时间。
  - 用户等待时间。
  - 队列等待时间。

  否则 Workflow 总耗时容易产生误导。

  ## P2：上下文工程继续完善

  建议将上下文正式拆成四层：

  1. 固定上下文

  - System Prompt。
  - Agent 规则。
  - Tool 摘要。
  - 安全策略。

  固定上下文不参与普通摘要压缩，应进行版本化和缓存。

  2. 工作状态

  - 当前目标。
  - Checkpoint 阶段。
  - 结构化图表节点和连线。
  - 已接受假设。
  - 当前 Artifact ID。

  这是最重要的长期任务状态，不应仅保存在自然语言摘要里。

  3. 最近对话窗口

  保留最近几轮原始消息，旧消息进入摘要。

  4. Tool Result

  大结果保存为 Artifact：

  上下文：摘要 + Artifact ID
  数据库：完整 Tool Result

  还需要测试压缩前后：

  - Agent 是否忘记审核结论。
  - 是否重复创建节点。
  - 是否重复调用 Tool。
  - 是否还能继续修改已有 Draw.io 图。

## P2：安全与权限

  目前个人开发环境足够，但生产化还缺：

  - 用户、角色、工作区权限。
  - Session、Artifact、Checkpoint 数据隔离。
  - Tool 权限策略。
  - 高风险 Tool 人工审批。
  - 日志和 Tool Result 脱敏。
  - 审计日志。
  - 登录失败限制和 Token 黑名单。

  尤其不要让前端提交任意 API Key 后直接长期落库明文保存。

 ## P2：Artifact 版本管理

  Draw.io 场景很适合增加版本能力：

  - 每次绘图生成一个 Artifact Revision。
  - 保存父版本。
  - 支持版本对比。
  - 回退到任意版本。
  - 从旧版本创建新分支。
  - 标记哪个 Invocation 生成了该版本。

  例如：

  diagram-v1
  └── diagram-v2
      ├── diagram-v3
      └── diagram-v2-hotfix

  这样“回到某次回答继续修改”才能真正实现，而不是只恢复聊天文本。

  ## P3：动态 Subagent

  如果继续做动态 Subagent，建议不要让模型任意生成 Java 类。更稳妥的是：

  模型选择模板
  → 生成受约束的 Subagent Spec
  → 平台校验
  → 运行时创建 ADK LlmAgent
  → 执行
  → 销毁或保存模板

  Subagent Spec 可以包含：

  {
    "name": "code_explorer",
    "objective": "...",
    "modelPolicy": "balanced",
    "allowedCapabilities": [],
    "maxTurns": 12,
    "tokenBudget": 50000,
    "timeoutSeconds": 300,
    "writePermission": false
  }

  需要配套：
  - DAG 依赖。
  - 显式父子 Run ID。
  - 循环检测。
  - 最大深度。
  - 最大并发。
  - Token 总预算。
  - 失败传播策略。
  - 部分结果回收。
