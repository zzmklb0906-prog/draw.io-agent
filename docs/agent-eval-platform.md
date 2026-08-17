# Agent Eval 平台

## 目标与边界

Agent Eval 与运行监控共用同一份 Invocation 事实数据，但职责分离：监控台回答“这次 Agent 怎样运行”，Eval 回答“这个 Prompt、Skill、Tool 或模型版本是否比基线更好”。Benchmark Case 描述输入和评分契约，不把大段标准答案当成唯一正确结果。

评测调用使用独立 Session，并关闭长期记忆抽取，避免测试样本污染用户 Memory。运行事件、模型调用、Tool 调用、Tokens 和耗时仍进入正常观测链路。每个 Case Run 保存 `invocation_id`，Eval 与监控台可以双向跳转。

## 数据模型

Flyway `V5__agent_eval_platform.sql` 创建：

- `eval_dataset`：测试集、版本、当前 Baseline；
- `eval_case`：Agent、输入 Prompt、期望、Rubric、标签和启停状态；
- `eval_run`：一次候选版本运行及聚合指标；
- `eval_case_run`：单 Case 的重复运行、输出、分数和 Invocation；
- `eval_assertion_result`：逐条评分证据。

Case 的 `expectations` 支持：

```json
{
  "requiredText": ["mxGraphModel"],
  "forbiddenText": ["无法完成"],
  "requiredTools": ["search_capabilities", "execute_capability"],
  "forbiddenTools": ["delete_file"],
  "requiredCapabilities": [
    {"type": "SKILL", "name": "drawio", "action": "EXECUTE"}
  ],
  "requiredResources": [
    {"type": "SKILL", "name": "drawio", "action": "EXECUTE", "resourcePath": "references/flowchart.md"}
  ],
  "maxDurationMs": 60000,
  "maxTokens": 20000,
  "maxModelCalls": 4,
  "maxToolCalls": 8,
  "passScore": 75
}
```

`rubric` 中的 `contentWeight`、`trajectoryWeight`、`efficiencyWeight` 会归一化为 100 分。禁止文本和禁止 Tool 是 Hard Gate。`toolPrecision` 会把必需 Tool 之外的调用视为轨迹噪声，用于发现 Tool description 或路由策略退化。

`requiredTools` 验证的是 Capability Broker 的入口工具；`requiredCapabilities` 验证真正被执行的 Skill/MCP/Java Tool。两者不能互相替代。结构化断言会匹配能力类型、分组、名称、版本、动作和资源路径，不再通过模型回答中的文字猜测 Skill 是否实际执行。

## Capability Execution Trace

Flyway `V6__capability_execution_trace.sql` 增加三张事实表：

- `capability_search`：一次能力检索及其 Query、请求类型、耗时和状态；
- `capability_search_candidate`：候选能力的 rank、score、身份、版本、风险等级和最终是否被选择；
- `capability_execution`：LOAD/EXECUTE 的具体能力、资源路径、脱敏参数、结果摘要、大小、哈希、Artifact、重试、耗时与错误。

监控台因此会同时展示两层数据：

```text
Tool · execute_capability                 代理工具层
└── Capability · SKILL/drawio EXECUTE     实际能力层
    └── references/flowchart.md           实际加载资源
```

大结果不会永久塞进监控 JSON 和模型上下文。Broker 的大 Tool Result 会保存为 Artifact，Trace 仅保留摘要、字节数、SHA-256 和 `artifactId`。统一瀑布图通过 `parentToolCallId` 把实际能力挂到对应 Broker Tool 下面。

## 使用

启动前后端后访问 `http://localhost:5173/eval`：

1. 新建或选择 Dataset；
2. 增加 Case，定义期望内容、轨迹和预算；
3. 输入候选版本名称，设置重复次数（1–10）；
4. 运行 Benchmark；
5. 查看总分、通过率、Hard Gate、Tokens、耗时和逐条断言；
6. 点击“查看 Trace”核对模型、Tool、Skill 和 Subagent 时间线；
7. 将确认过的完成运行设为 Baseline，后续运行自动计算 regression。

候选名称应携带可追踪版本，例如 `draw-agent/prompt-v7+drawio-skill-v3+tools-20260816`。当前保存候选标签和 Case 版本；团队发布阶段应进一步把 Prompt、Skill、Tool schema 与模型配置做成不可变制品，并在 Run 中保存内容哈希。

## API

接口使用现有 JWT：

- `GET/POST /api/v1/eval/datasets`
- `GET /api/v1/eval/datasets/{datasetId}`
- `POST /api/v1/eval/datasets/{datasetId}/cases`
- `PUT /api/v1/eval/cases/{caseId}`
- `GET/POST /api/v1/eval/runs`
- `GET /api/v1/eval/runs/{runId}`
- `POST /api/v1/eval/datasets/{datasetId}/baseline/{runId}`
- `GET /api/v1/eval/invocations/{invocationId}`

运行请求：

```json
{"datasetId":"UUID","candidateLabel":"draw-agent-v7","repeats":3,"baselineRunId":"可选 UUID"}
```

## 回归门禁建议

- 平均分不低于 Baseline，且无 Hard Gate 失败；
- 关键 Case 通过率 100%；
- P95 延迟和平均 Tokens 不恶化超过 15%；
- 必需 Skill/Tool 召回率 100%，禁止 Tool 调用数为 0；
- 非确定性 Case 至少重复 3 次，比较通过率而非单次答案。

开放式内容后续可增加 LLM-as-a-judge，但必须固定 Judge Prompt、盲化候选顺序，并用人工标注集校准偏差。

## Subagent 长度

动态 Subagent 单任务限制不再硬编码为 20,000 字符，默认 65,536，可配置：

```yaml
ai.agent.subagent.max-task-chars: 65536
```

字符上限不等于上下文窗口。长代码任务应传 Artifact/文件引用、目标与输出契约，而不是复制整个仓库；Eval 还应同时限制 Tokens、Turns、Tool Calls 和耗时。
