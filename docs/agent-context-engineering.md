# Draw.io Agent 上下文工程与能力扩展

## 1. 目标

本项目不在 Agent instruction 中维护不断增长的 Skill 和 Tool 名单。模型应根据工具自身的名称、描述、输入参数以及 Skill frontmatter 自主选择能力。Prompt 只描述角色、执行边界、输入输出契约和质量门禁。

当前 Draw.io 工作流采用：

```text
用户原始需求
  -> 需求编辑 Agent
  -> approval 审核卡片
  -> 用户确认 / 修改 / 取消
  -> 绘图 Agent
  -> Tool / Skill / MCP 自主选择
  -> NDJSON + Draw.io XML
```

## 2. Prompt 设计原则

### 保留在 instruction 中

- Agent 的单一职责和权限边界；
- Human-in-the-loop 执行门禁；
- 输入、输出和错误协议；
- 不允许违反的质量与安全约束；
- 一到两个用于说明协议的短示例。

### 不应写入 instruction

- 所有可用 Skill、Tool、MCP 的逐项名单；
- 经常变化的业务知识或外部文档；
- 要求模型输出隐藏思维链的 CoT 指令；
- 与当前 Agent 职责无关的通用口号；
- 可由代码校验器确定性完成的长篇规则。

ADK 本身已经提供模型—工具—观察结果循环，不需要在 Prompt 中重复书写 `Thought -> Action -> Observation`。对于复杂任务，可以要求模型“先建立内部计划并在输出前校验”，但不要求展示隐藏推理过程。

Few-shot 只用于容易出错的协议边界，例如 `approval` JSON 和 NDJSON 图元格式。示例应短小、代表性强，避免把某个业务案例固化成默认行为。

## 3. Human-in-the-loop 协议

首次请求或修改请求由需求编辑 Agent 输出：

```json
{
  "type": "approval",
  "title": "微信公众号扫码登录流程",
  "rewrittenPrompt": "绘制……",
  "diagramType": "flowchart",
  "scope": ["浏览器", "业务后端", "微信平台"],
  "assumptions": ["采用网页 OAuth 扫码登录"],
  "questions": []
}
```

规则：

- `questions` 非空时前端禁止确认，用户必须提交修改；
- 用户修改后生成新的审核版本；
- 用户明确确认后，需求编辑 Agent 输出完整的 `[APPROVED_DRAWING_BRIEF]`；
- 绘图 Agent 只接受已确认简报，其余输入不会执行；
- 审核发生在工作流轮次边界，因此不会暂停正在执行的模型调用，也不依赖进程内线程阻塞。

## 4. Agent、Tool、Skill、MCP 与 RAG 的分工

| 能力 | 适合场景 | 示例 |
| --- | --- | --- |
| Agent | 需要语义判断、生成、权衡与多步协调 | 需求改写、图表规划、质量审查 |
| Tool | 确定性计算或对系统产生受控操作 | XML 校验、自动布局、导出、数据库查询 |
| Skill | 可复用的方法、规范和工作步骤 | 流程图规范、架构图样式、PPT 排版 |
| MCP | 外部系统或跨进程工具接入 | GitHub、文件系统、搜索、企业服务 |
| RAG | 大量、动态、需要引用来源的知识检索 | 企业架构规范、接口文档、历史图表、术语库 |

不要用 Tool 包装一次没有确定性边界的“让模型识别意图”。需求改写更适合作为独立 Agent。可以把下面这些能力封装为 Tool：

- `validate_drawio_xml`：解析 XML，检查标签、ID、source/target 和边界；
- `auto_layout_diagram`：输入结构化节点和边，返回确定性坐标；
- `get_current_diagram_state`：读取当前画布结构，支持增量修改；
- `save_diagram_artifact`：持久化版本并返回 Artifact ID；
- `diff_diagrams`：比较两个版本的节点、边和样式；
- `search_project_knowledge`：通过 RAG 返回摘要、来源和 Artifact ID。

## 5. RAG 接入建议

仅在外部知识会影响图表事实时检索，例如“根据本项目代码绘制真实调用链”或“按照企业规范绘制部署图”。普通的通用流程图不需要 RAG。

推荐检索结果协议：

```json
{
  "query": "认证模块调用关系",
  "summary": "Controller 调用 LoginService，后者访问 UserRepository",
  "citations": ["src/.../LoginController.java:42"],
  "artifactId": "rag-auth-001"
}
```

完整原文保存为 Artifact；模型上下文只保留摘要、引用和 Artifact ID。这样可以控制 Token 消耗，并与现有上下文压缩机制配合。

## 6. 能力注册约束

- 工具名称使用动作 + 对象，例如 `validate_drawio_xml`；
- 描述必须说明何时使用、何时不要使用、是否产生副作用；
- 参数使用结构化 Schema，不让模型拼接模糊字符串；
- 查询类 Tool 默认只读；写入、覆盖、发布等操作需要确认；
- Tool 返回结构化结果和稳定错误码；大结果转为 Artifact；
- 监控记录 Agent、Tool、Skill、MCP、RAG、耗时、状态和 Token，而不是只记录文本 Event。

## 7. 后续演进顺序

1. 增加确定性的 Draw.io XML 校验 Tool；
2. 将当前浏览器图状态通过 Tool/API 提供给 Agent，支持增量编辑；
3. 增加 Artifact 存储，替代只保存在浏览器；
4. 为项目代码和接口文档增加轻量 RAG；
5. 对有副作用的 Tool 接入 ADK 原生确认机制；
6. 使用评测集验证需求改写准确率、Skill 选择率、XML 合法率和用户修改次数。
