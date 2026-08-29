# Agent 运行时监控平台市场调研

> 调研日期：2026-08-29
>
> 调研主题：Agent 运行时可观测性、监控与评测平台选型

## 1. 调研背景与分类标准

随着基于大型语言模型（LLM）与智能体（Agent）的复杂应用落地，传统的 APM（应用性能监控）与日志检索已无法满足多轮交互、子 Agent 协同、工具调用链路追踪以及非确定性输出质量评测的需求。本调研对当前主流及先进的 Agent 可观测性与运行时监控方案进行全景梳理。

市面方案通常分为三类：

1. **托管优先的端到端 Agent 平台**（如 LangSmith）：提供全生命周期的 Trace 追踪、在线 Prompt 调试、自动化评测与标注队列，开箱即用但存在一定程度的产品或框架绑定。
2. **开源/自托管友好的可观测与工程平台**（如 Langfuse、Arize Phoenix、Opik）：原生基于开放标准（如 OpenTelemetry、OpenInference），支持数据完全私有化部署，适合需要数据自持、架构开放或与特定语言栈（如 Java）集成的团队。
3. **企业级综合 APM 拓展平台**（如 Datadog Agent Observability）：将传统基础设施监控、微服务 APM 与 GenAI/Agent 可观测性统一，提供生产级高可用、安全合规与敏感数据治理能力。

## 2. 成熟方案对比

| 方案 | 定位 | 已成熟能力 | 适合场景 | 对 Agent 系统的参考价值 |
| --- | --- | --- | --- | --- |
| LangSmith | 托管优先的 Agent/LLM 可观测与评测平台 | Trace 检索/比较/导出、Dashboard、Alert、规则/Webhook、在线评测、人工标注队列 | 希望快速获得完整产品闭环，且可接受平台托管与绑定 | 提供了标准化的“Trace 追踪 → 在线评测 → 告警/人工复核”运营闭环设计范式 |
| Langfuse | 开源、自托管友好的 LLM Engineering 平台 | OTel 原生 Trace、Prompt 管理、Score、Experiment、Metrics；支持通过 OTel Endpoint 接入 Java 等语言 | 数据需自持、追求开放出口与自托管 | 与企业自建私有化 Java/PostgreSQL 路线高度契合，是理想的外部开放标准验证后端 |
| Arize Phoenix | 开源 AI 可观测与评测平台 | OTLP/OpenInference Trace、LLM/代码/人工评测、Dataset/Experiment、Prompt Replay | 研发调试、评测以及开放语义标准优先的项目 | 适合作为规范化验证 OTLP/OpenInference Span 属性映射与离线/在线评测的基准 |
| Datadog Agent Observability | 企业级生产运维平台 | Agent Span 树、延迟/错误/Token/成本 Dashboard、APM 关联、异常洞察、在线评测、敏感数据扫描 | 已深度采用 Datadog 的生产团队与大型企业环境 | 具备完备的生产指标看板、异常模式检测、企业级 APM 关联分析以及敏感信息合规治理模式 |
| Opik | 开源/托管 LLM 可观测与评测 | 高吞吐 Trace、成本/延迟趋势、反馈分、在线 LLM-as-a-Judge、采样规则、数据集回归 | 希望在开源栈内低成本打通生产反馈与质量回归 | 提供了按比例采样的在线评测机制，以及从生产失败案例直接沉淀回归测试集的流水线设计 |

### 官方依据

- [LangSmith Observability](https://docs.langchain.com/langsmith/observability)：明确覆盖 Trace、Dashboard/Alert、Automation、在线评测与人工反馈队列。
- [Langfuse SDK Overview](https://langfuse.com/docs/observability/sdk/overview)：说明其 SDK 基于 OpenTelemetry，支持异步发送、上下文传播，并允许 Java 等语言经 OTel Endpoint 接入。
- [Arize Phoenix Overview](https://arize.com/docs/phoenix)：说明 Phoenix 基于 OpenTelemetry/OpenInference，覆盖 Trace、Evaluation、Dataset/Experiment 和 Prompt Replay。
- [Datadog Agent Observability](https://docs.datadoghq.com/llm_observability/)：覆盖 Agent Trace、成本/Token/延迟、质量/隐私/安全评测和异常洞察。
- [Opik Production Monitoring](https://www.comet.com/docs/opik/tracing/dashboards/production_monitoring)：覆盖 Trace 数、延迟、成本、反馈趋势及在线评测。

## 3. 先进方案与技术演进方向

1. **OpenTelemetry GenAI 语义约定**：统一模型操作的 Span、Token Usage、Operation Duration、首包延迟等字段。当前相关约定仍处于快速演进阶段，因此系统应将语义映射集中在统一出口，避免实验性属性直接渗透到核心业务逻辑中。参考 [OpenTelemetry GenAI Semantic Conventions](https://github.com/open-telemetry/semantic-conventions/blob/main/docs/gen-ai/gen-ai-metrics.md)。
2. **OpenInference**：在 OTel 之上补充 Agent、Chain、LLM、Retriever、Tool 等 AI 专用语义规范，可无缝发送到任何 OTel 兼容后端。参考 [OpenInference](https://arize-ai.github.io/openinference/)。
3. **Trace 级在线评测**：评测整个 Agent 轨迹而非仅局限于最终文本输出，包括工具选择准确性、调用执行顺序、任务完成度和安全性；评测异常可自动进入告警和人工复核队列。参考 [Datadog Trace-Level Evaluations](https://docs.datadoghq.com/llm_observability/evaluations/custom_llm_as_a_judge_evaluations/trace_level_evaluations/) 与 [Opik Online Evaluation Rules](https://www.comet.com/docs/opik/production/online-evaluation/rules)。
4. **生产数据反哺评测集**：通过收集低分、执行失败和用户差评的 Trace，一键提取为测试 Dataset，用于版本迭代时的对比评估与发布质量门禁。Phoenix、LangSmith 与 Opik 均已形成此闭环。
5. **采集策略与安全合规**：生产环境默认不宜无界持久化原始 Prompt、Tool 参数与返回详情；需要统一构建脱敏（Redaction）、内容开关、尾部采样（Tail Sampling）、TTL 数据保留期与租户配额控制机制。

## 4. 选型考量与结论

### 4.1 选型考量维度

- **数据主权与隐私保护**：系统涉及企业内敏感业务信息及密钥，监控采集必须具备强有力的本地脱敏与存储自持能力。
- **架构开放性与去厂商绑定**：避免将 Agent 运行时生命周期或业务代码直接绑定到特定商业 SaaS 或私有 SDK，应以标准 OpenTelemetry / OpenInference 为长远标准互操作协议。
- **改造成本与依赖膨胀**：当前引入重型外部服务或分布式 Collector 会显著增加本地部署与运维复杂度。

### 4.2 外部平台集成暂缓结论

经调研评估，**明确将 OTLP、OTel Collector、Langfuse / Phoenix / Datadog / Opik 部署以及厂商平台集成暂缓实施（deferred）**。

其核心原因如下：
1. **基础设施与平台决策依赖**：外部平台或 Collector 的部署依赖团队对三方基础设施选型、服务器资源分配、网络拓扑及认证授权机制的独立决策，不属于当前应用核心运行时的前置阻塞项。
2. **数据质量与可靠性先行**：在本地采集源头数据（如 Token 统计口径、并发上下文关联、敏感信息脱敏和查询性能）未完全夯实前，直接推送到外部平台不仅无法解决本质数据失真，还会放大存储与网络成本。
3. **架构策略**：保持系统业务与监控内部逻辑独立，以本地轻量级存储支撑现有业务，未来仅在数据导出边界以无侵入的 OTLP 适配器形式提供外部集成能力。
