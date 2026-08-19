# Agent Platform 功能测试清单

本清单覆盖当前 `wait-to-do.md` 中已经实现的功能。测试状态使用：

- `[x]` 已验证
- `[ ]` 待执行
- `暂停` 按当前要求暂不执行

## 1. 测试前准备

- [x] 启动基础设施：PostgreSQL 15、Redis 7.2。
- [x] 确认数据库连接配置正确：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。
- [x] 配置 `DEEPSEEK_API_KEY`，不要把真实 Key 提交到 Git。
- [x] 启动后端，确认 Flyway V1–V20 执行成功。
- [x] 启动前端，确认可以打开登录页和工作台。
- [x] 确认浏览器携带 JWT 访问 `/api/v1/**`。
- [x] 独立临时 PostgreSQL 数据库重放 V1–V20 成功，共创建 38 张业务表。

## 2. 构建与静态检查

- [x] 后端编译：

  ```powershell
  $env:MAVEN_OPTS='-Xms96m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC'
  mvn -pl ai-agent-scaffold-draw-io-app -am -DskipTests compile
  ```

- [x] 前端类型检查：

  ```powershell
  cd front
  npm run typecheck
  ```

- [x] `git diff --check` 无空白错误。
- [x] 在目标机器上执行一次完整启动，确认没有 Bean 装配异常。
- [x] 检查启动日志中没有数据库迁移失败、Redis 连接失败或模型配置失败。

## 3. 登录、JWT 与权限

- [x] 使用 `admin / admin` 登录成功。
- [x] 错误密码登录返回 403，不创建会话。
- [x] 连续错误登录达到限流阈值后返回限流错误。
- [ ] 登录返回短期 Access Token，并通过 HttpOnly Cookie 保存 Refresh Token。
- [ ] Access Token 过期后，前端自动刷新并继续请求。
- [ ] Refresh Token 轮换后旧 Refresh Token 不可再次使用。
- [ ] 登出后 Access Token 被 Redis 黑名单拒绝。
- [ ] 未携带 JWT 请求业务接口返回 401。
- [ ] 使用其他用户的 `X-User-Id` 返回 403。
- [x] 登录、刷新、登出和拒绝请求写入 `security_audit_event`。

### 📋 账号与初始密码清单                                                                                                                    

   用户名                            │ 初始密码                          │ 姓名 / 显示名                    │ 角色
  ───────────────────────────────────┼───────────────────────────────────┼──────────────────────────────────┼──────────────────────────────────
   admin                             │ admin                             │ Administrator                    │ ["ADMIN", "USER"]
   developer                         │ dev123456                         │ 高级研发工程师                   │ ["DEVELOPER", "USER"]
   tester                            │ test123456                        │ 自动化测试员                     │ ["TESTER", "USER"]
   architect                         │ arch123456                        │ 系统架构师                       │ ["ARCHITECT", "USER"]
   designer                          │ draw123456                        │ UI/UX 绘图设计师                 │ ["DESIGNER", "USER"]
   user1                             │ user123456                        │ 普通用户 Alice                   │ ["USER"]
   user2                             │ user123456                        │ 普通用户 Bob                     │ ["USER"]

## 4. Session、消息与幂等

- [ ] 创建 Session 成功返回 `sessionId` 和 `conversationId`。
- [ ] 使用同一个创建 Session 幂等键重复提交，只创建一个 Session。
- [ ] 使用同一个幂等键但修改请求内容，返回幂等冲突。
- [ ] 提交普通消息后，消息只落库一次。
- [ ] 重复发送同一请求幂等键，不重复调用模型。
- [ ] 同一 Session 同时提交两条消息，第二条被拒绝或等待，不产生并发写入。
- [ ] 会话刷新后，用户消息、AI 消息、审批消息和 Artifact 仍可恢复。
- [x] 侧边栏会话列表只显示当前用户可访问的会话。

## 5. Draw.io Agent 基础流程

- [ ] 新建会话并发送简单绘图请求。
- [ ] Analyst 阶段返回审核卡片。
- [ ] 审核卡片展示标题、图表类型、范围、假设和方案内容。
- [ ] 点击“修改方案”后只重新执行分析阶段。
- [ ] 修改意见会进入新的审核结果。
- [ ] 点击“确认并开始绘图”后直接进入 Drawer 阶段，不重复执行 Analyst。
- [ ] 绘图完成后返回 `drawio_done` 和有效 `mxGraphModel`。
- [ ] Draw.io 画布能够加载 XML，并可继续编辑。
- [ ] 已完成审核的按钮不可重复点击。
- [ ] 刷新页面后审核状态和当前画布仍然存在。
- [ ] 同一 Workflow 的多次审核/恢复属于同一个业务任务，并能展开多个 Invocation。

## 6. Checkpoint、Pause、Resume 与人工审批

- [ ] 方案审核阶段状态为 `WAITING_APPROVAL`。
- [ ] 使用旧 `checkpointRevision` 提交审批，返回版本冲突。
- [ ] 重复提交同一审批幂等键，不重复运行 Agent。
- [ ] `APPROVE` 后从稳定 Checkpoint 恢复绘图。
- [ ] `REVISE` 后回到分析阶段，不直接绘图。
- [ ] 点击停止后状态变为 `PAUSED`，并保留 Checkpoint。
- [ ] 从 `PAUSED` 恢复后继续执行，不新建无关会话。
- [ ] 触发高风险 Tool 时出现 Tool 审批卡片。
- [ ] Tool 审批卡片只展示脱敏后的参数。
- [ ] 选择拒绝后 Tool 不执行，状态写入审计日志。
- [ ] 选择批准后通过 ADK FunctionResponse 恢复 Tool 执行。
- [ ] 刷新页面后 `WAITING_TOOL_APPROVAL` 审批卡片仍存在。
- [ ] 客户端断开 SSE 不会自动把后台 Workflow 当成失败。

## 7. Tool、MCP 与 Skill 治理

- [ ] 能力检索记录 Query、候选能力、分数和最终选中项。
- [ ] 能看到 Skill/Tool 的版本、Schema 版本和内容版本。
- [ ] Tool 调用记录开始时间、结束时间、耗时、状态和错误。
- [ ] Tool 超时后状态为失败，并且不会被迟到结果覆盖。
- [ ] Tool 按策略执行最大重试次数和指数退避。
- [ ] 连续失败达到阈值后触发熔断。
- [ ] 并发限制生效，禁止并行的 Tool 不会并发运行。
- [ ] 大 Tool Result 被保存为 Artifact，上下文只保留摘要、哈希和 Artifact ID。
- [ ] Tool 参数和结果中的 Token、密码、API Key 等敏感信息被脱敏。
- [ ] Tool 使用稳定请求 ID 重试时不会产生重复副作用。
- [ ] 能通过能力反馈标记：漏选、误选、影响输出或无影响。
- [ ] Agent 未调用 Tool 时，监控显示“工具已注册但未实际调用”，不伪造调用记录。

## 8. 监控与 Workflow 观测台

- [ ] 监控页面能够按 Session 查询 Invocation。
- [ ] 选择具体 Session 后，汇总数据只属于该 Session。
- [ ] Workflow 名称显示根 Agent / App 名称，而不是内部子 Agent 名称。
- [ ] 一个 Workflow 展开后能看到所有 Invocation、Agent Run、Model Call 和 Tool Call。
- [ ] 显示 Agent、模型和 Tool 的开始时间、结束时间、耗时和状态。
- [ ] 显示输入 Tokens、输出 Tokens、总 Tokens 和模型费用。
- [ ] 区分模型耗时、Tool 耗时、人工审批等待和队列等待。
- [ ] 统计 Tool 成功数、失败数和重试次数。
- [ ] 展示 Checkpoint 状态迁移时间线。
- [ ] 展示 Artifact 版本和生成它的 Invocation。
- [ ] Invocation 完成后状态停止计时，不继续显示 RUNNING。
- [ ] 失败 Invocation 计入异常统计，成功 Invocation 计入成功统计。
- [ ] 监控详情接口异常时，前端显示可读错误，不跳转登录页。
- [ ] 子 Agent 的父子 Run、模型和 Tool 关系显示正确。

## 9. 上下文压缩与记忆

- [ ] 上下文低于阈值时不触发压缩。
- [ ] 达到阈值后记录压缩前 Tokens、压缩后 Tokens、策略、模型和耗时。
- [ ] 固定 Prompt、Tool Schema 和安全策略不会被普通历史摘要删除。
- [ ] 结构化项目状态保留目标、图表类型、节点、连线、约束和当前 Artifact ID。
- [ ] 最近对话窗口仍保留最近若干轮原始消息。
- [ ] 大 Tool Result 被替换为摘要和 Artifact 引用。
- [ ] 压缩后 Agent 不重复创建已有节点。
- [ ] 压缩后 Agent 仍能继续修改已有 Draw.io 图。
- [ ] 达到 95% 上下文阈值时阻止继续执行并提示新建或压缩会话。
- [ ] 长期记忆保存来源、置信度和证据引用。
- [ ] 记忆冲突不会静默覆盖，而是保留冲突记录或请求人工确认。

## 10. Artifact 版本与数据安全

- [ ] 每次绘图结果生成新的 Artifact Revision。
- [ ] 新版本正确关联父版本、谱系、分支和 Invocation。
- [ ] 同一谱系同一分支的版本号不会重复。
- [ ] 两个同谱系 Artifact 可以查看 Diff。
- [ ] 回退操作生成新 Revision，不覆盖历史版本。
- [ ] 从旧版本创建分支后，分支内容可独立继续修改。
- [ ] 跨会话 Invocation 不能写入当前 Artifact。
- [ ] 跨用户或无权限 Workspace 无法读取、修改或回退 Artifact。
- [ ] Artifact 大内容不会直接进入普通监控列表或上下文。

## 11. 恢复调度器与故障注入

- [ ] 启动时扫描遗留 `RUNNING` Invocation。
- [ ] 心跳有效的其他实例任务不会被当前实例接管。
- [ ] 实例停止后，其过期租约最终进入恢复或中断状态。
- [ ] 有稳定 Checkpoint 的任务进入恢复队列。
- [ ] 无法恢复的任务标记为 `INTERRUPTED` 或 `FAILED`。
- [ ] 同一个恢复任务只能被一个实例 Claim。
- [ ] Invocation 超时后不会继续计时。
- [ ] Tool deadline 到期后 Tool 和 Attempt 都被回收。
- [ ] 恢复任务达到最大重试次数后进入 `FAILED`。
- [ ] 恢复任务使用稳定幂等键，不重复生成 Artifact。
- [ ] 强制关闭后重新启动，迟到的旧回调不会覆盖恢复后的终态。

## 12. 动态 Subagent

- [ ] 通用 Agent 能查询可用 Subagent 模板。
- [ ] 使用不存在的模板会被拒绝。
- [ ] 超过任务字符数、最大深度或单 Invocation 任务数会被拒绝。
- [ ] 超过并发限制时任务进入失败或等待状态。
- [ ] 超过总 Token 预算时不会继续创建任务。
- [ ] Subagent 只能使用模板允许的能力组和权限模式。
- [ ] 父子 Run ID、父任务 ID 和分支路径正确记录。
- [ ] 依赖未完成时子任务不会提前执行。
- [ ] 依赖失败时失败策略生效。
- [ ] 子任务超时后被取消并落库为失败。
- [ ] 父 Invocation 停止等待后，未完成子任务被取消或标记失败。
- [ ] 子任务失败时保留可用的部分结果。
- [ ] 子任务运行记录可在监控台查看。

## 13. 数据库与并发验证

- [ ] 并发创建相同幂等请求只产生一条资源记录。
- [ ] 并发审批同一 Revision 只有一个请求成功。
- [ ] 并发 Artifact 分支不会产生重复版本号。
- [ ] Redis Session Lease 过期后可安全接管。
- [ ] Redis 不可用时，系统返回明确错误，不静默绕过并发保护。
- [ ] 数据库重启后 Session、Checkpoint、监控和 Artifact 数据仍可读取。
- [ ] Flyway 重复启动不会重复执行迁移。
- [ ] 数据库中不存在明文 API Key、密码或完整敏感 Tool Result。

## 14. 多策略模型自动路由测试（方案1 启发式语义路由 & 方案2 规则分类器）

- [x] **策略模式单元测试断言 (`IModelRouterStrategy`)**：运行 `ModelRoutingServiceTest` 验证 `semantic`、`classifier` 与 `composite` 智能路由策略全系跑通（`3 tests, 0 failures`）。
- [ ] **方案 1 启发式语义路由断言 (`SemanticVectorModelRouter` / Heuristic Semantic Router)**：
  - 发送语义包含“算法导论、分布式一致性、状态机 checkpoint 与架构重构”的请求，验证关键词密度评分加权与饱和度计算得出 `SEMANTIC_VECTOR_HIGH_REASONING` 并路由至 `reasoning-model`（如 `deepseek-v4-pro-0813` 或 `qwen3.8-max`）。
  - 发送语义包含“格式化整理、段落摘要、多语言校验与翻译”的短文本，验证路由得出 `SEMANTIC_VECTOR_LOW_COMPLEXITY` 并路由至 `fast-model`（如 `qwen3.7-flash`）。
- [ ] **方案 2 规则分类器断言 (`LlmClassifierModelRouter` / Rule-Based Classifier)**：
  - 发送开放域复杂提问，验证基于字符长度与关键词特征模式的启发式规则预判输出 Complexity=3，Reason 记录为 `HEURISTIC_CLASSIFIER: High complexity keywords detected in current message`。
- [ ] **组合智能路由与兜底断言 (`CompositeModelRouter`)**：
  - 验证多层级 Pipeline（Tier 1 启发式语义分析 -> Tier 2 规则分类器 -> Tier 3 规则兜底）按优先级流畅运行。
- [ ] **用户显式选择最优先断言 (`USER_EXPLICIT`)**：
  - 在前端下拉框显式指定 `kimi-k2.7-code` 或 `glm-5.2`，验证自动路由策略被安全跳过，实际调用模型与用户指定一致，Reason 记录为 `USER_EXPLICIT`。
- [ ] **多 Model Provider 兼容性断言**：
  - 按照 `api-model.md` 提供的 Qwen、DeepSeek、GLM、Kimi 各种 API Key 与 Endpoint，验证聊天与 Draw.io 绘图 XML 生成正常。

## 15. 暂不执行项目

- Agent Eval 自动化平台和自动评分：按当前需求暂停。
- 大规模模型对比、Prompt A/B、Skill 自动优化：待 Agent Eval 恢复后执行。

## 16. 测试记录

| 日期 | 测试范围 | 结果 | 问题/备注 |
| --- | --- | --- | --- |
| 2026-08-17 | Maven 七模块编译 | 通过 | 使用 `-DskipTests compile` |
| 2026-08-17 | 前端 TypeScript 检查 | 通过 | `npm run typecheck` |
| 2026-08-17 | PostgreSQL V1–V20 迁移重放 | 通过 | 远程 223.109.141.184 自动全量迁移重放，创建 38 张业务表 |
| 2026-08-17 | 模型路由策略模式重构与单测 | 通过 | `ModelRoutingServiceTest`（方案1+2+组合路由器） |
