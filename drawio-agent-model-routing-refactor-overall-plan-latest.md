# draw.io-agent 模型路由重构与动态模型选择总体规划（最新版）

## 文档定位

目标：

将当前基于人工档位判断的模型路由系统：

    请求
     ↓
    L1/L2/L3复杂度判断
     ↓
    fast / balanced / reasoning
     ↓
    固定模型

升级为：

    User Request
    +
    Agent Context
    +
    Task Requirement
    +
    Model Runtime State
    
            ↓
    
    Capability-aware Dynamic Model Routing
    
            ↓
    
    候选模型过滤
    
            ↓
    
    模型评分排序
    
            ↓
    
    最佳模型 + Backup模型

最终建设：

Universal Agent Model Routing Platform

而不是 Draw.io 专用模型选择器。

------------------------------------------------------------------------

# 一、当前系统状态分析

当前项目已经具备：

-   多 Provider 接入
-   Qwen / DeepSeek / GLM / Kimi 等模型配置
-   ADK beforeModelCallback 统一入口
-   Model Routing Trace
-   Agent Eval
-   Runtime Observation
-   用户显式模型覆盖

但是当前自动路由本质：

Tier-based Model Routing

核心问题：

## 1. 自动选择范围有限

当前只有：

    fastModel
    balancedModel
    reasoningModel

三个槽位。

即使注册多个模型，也不会真正比较所有模型能力。

------------------------------------------------------------------------

## 2. 当前复杂度判断不等于任务理解

问题：

-   关键词触发
-   长文本导致误升级
-   历史消息污染
-   无 Agent 维度

例如：

第一轮：

"分析整个系统架构"

第二轮：

"修改标题"

不应该继续按照复杂任务处理。

------------------------------------------------------------------------

## 3. 当前 Router 不是语义 Router

现有：

SemanticVectorModelRouter

实际上是：

keyword heuristic

不存在：

-   Embedding
-   Vector Search
-   Cosine Similarity
-   Semantic Prototype

因此后续应该重新定义职责。

------------------------------------------------------------------------

# 二、核心设计原则

## 原则1：Draw.io不是特殊分支

禁止：

``` java
if(drawio){
    useMaxModel();
}
```

Draw.io只是：

Agent Profile。

例如：

    agent_drawer
    
    要求：
    
    structuredOutput 高
    xmlValidity 高
    toolCalling 中高
    reasoning 中

------------------------------------------------------------------------

## 原则2：从复杂度判断升级为能力匹配

旧：

    复杂度
     ↓
    模型

新：

    任务需要什么能力？
    
    Agent需要什么能力？
    
    哪些模型满足约束？
    
    哪个模型综合收益最高？

------------------------------------------------------------------------

## 原则3：模型没有固定强弱排序

不存在：

    Max > Plus > Flash

正确：

根据：

-   Quality
-   Reliability
-   Latency
-   Cost
-   Agent Fit

综合决定。

------------------------------------------------------------------------

## 原则4：先过滤，再评分

流程：

    Hard Constraint Filter
    
    ↓
    
    Model Ranking

不满足：

-   Context Window
-   Vision
-   Tool Calling
-   Output Limit

直接淘汰。

------------------------------------------------------------------------

# 三、目标架构

    CustomConfigPlugin
    
            ↓
    
    RoutingContextFactory
    
            ↓
    
    RoutingRequirementAnalyzer
    
            ↓
    
    ModelCatalogService
    
            ↓
    
    ModelConstraintFilter
    
            ↓
    
    ModelRanker
    
            ↓
    
    RoutingDecision
    
            ↓
    
    ProviderRegistry
    
            ↓
    
    LLM Call

------------------------------------------------------------------------

# 四、核心领域模型

## 1. RoutingContext

描述一次请求：

包含：

-   latestUserMessage
-   Agent Name
-   Workflow Stage
-   Context Token
-   Explicit Model

------------------------------------------------------------------------

## 2. RoutingRequirement

描述任务需求：

例如：

``` json
{
"taskType":"DRAWIO_GENERATION",
"reasoningRequired":70,
"structuredOutputRequired":95,
"toolCallingRequired":80,
"contextDependency":40
}
```

------------------------------------------------------------------------

## 3. ModelProfile

描述模型能力：

包含：

-   capability
-   limits
-   pricing
-   runtime metrics

例如：

    ModelProfile
    
    modelName
    
    capabilities:
    
     reasoning
     coding
     structuredOutput
     toolCalling
     vision
     longContext


    limits:
    
     contextWindow
     maxOutputTokens

------------------------------------------------------------------------

# 五、重构阶段规划

------------------------------------------------------------------------

# Phase 0 安全基线

目标：

不改变行为，建立安全基础。

完成：

-   清理配置中的敏感信息
-   API Key 环境变量化
-   增加路由回归测试

测试：

-   简单编辑
-   架构分析
-   长文本摘要
-   并发诊断
-   多轮历史污染
-   显式模型覆盖

------------------------------------------------------------------------

# Phase 1 修正输入和命名

目标：

解决最大误判来源。

新增：

    LatestUserMessageExtractor

原则：

复杂度判断：

使用：

    latestUserMessage

而不是：

    全部conversation contents

同时：

context长度只用于：

-   长上下文判断
-   token需求

------------------------------------------------------------------------

# Phase 2 Model Catalog

目标：

所有模型进入统一候选池。

新增：

    ModelCatalogService
    
    ModelProfile
    
    ModelCapabilities
    
    ModelLimits
    
    ModelPricing

废弃：

    fastModel
    balancedModel
    reasoningModel

作为核心选择依据。

------------------------------------------------------------------------

# Phase 3 RoutingRequirement

目标：

替代L1/L2/L3。

新增：

    TaskType
    
    RoutingRequirement
    
    FeatureExtractor

规则仍然可以存在。

但输出：

能力需求

而不是：

最终模型。

------------------------------------------------------------------------

# Phase 4 Hard Constraint Filter

新增：

    ModelConstraintFilter

过滤：

-   disabled
-   provider不可用
-   context不足
-   不支持vision
-   不支持tool
-   输出限制不足

------------------------------------------------------------------------

# Phase 5 Dynamic Ranking

新增：

    ModelCandidate
    
    ModelScorer
    
    ModelRanker

初始评分：

    Capability Fit 45%
    
    Agent Fit 20%
    
    Reliability 15%
    
    Latency 10%
    
    Cost 10%

必须输出：

score breakdown。

------------------------------------------------------------------------

# Phase 6 Agent-aware Routing

不同Agent拥有不同策略。

例如：

## agent_analyst

重点：

-   instruction following
-   structured JSON
-   reasoning

## agent_drawer

重点：

-   XML稳定
-   structured output
-   tool calling

## agent_reviewer

重点：

-   validation
-   correction

注意：

reviewer是否加入workflow属于独立任务。

------------------------------------------------------------------------

# Phase 7 Fallback与健康检测

RoutingDecision返回：

    selectedModel
    
    rankedCandidates
    
    backupModels
    
    reason

支持：

-   provider失败
-   rate limit
-   timeout

------------------------------------------------------------------------

# Phase 8 Eval闭环

复用已有：

-   Agent Eval
-   Runtime Trace

新增：

记录：

-   selected model
-   candidate ranking
-   score
-   latency
-   tokens
-   final quality

流程：

    Production Trace
    
    ↓
    
    Eval Dataset
    
    ↓
    
    多模型测试
    
    ↓
    
    调整能力评分

------------------------------------------------------------------------

# Phase 9 Semantic / SLM增强

最后阶段。

不要提前引入。

可以增加：

Embedding：

    Request
    
    ↓
    
    Vector
    
    ↓
    
    Task Similarity
    
    ↓
    
    Feature Evidence

SLM：

输出：

``` json
{
"taskType":"CODE_ANALYSIS",
"reasoningRequired":80,
"structuredOutputRequired":50
}
```

注意：

SLM不能直接决定模型。

它只能提供 Evidence。

------------------------------------------------------------------------

# 六、迁移策略

禁止：

一次删除旧Router。

采用：

## V1

Legacy + Dynamic并存

## V2

Shadow Mode

    Legacy执行
    
    Dynamic观察

## V3

Dynamic默认

## V4

删除Legacy

------------------------------------------------------------------------

# 七、编码Agent执行规范

修改前：

必须：

1.  阅读当前代码
2.  输出调用链
3.  明确影响范围
4.  再修改

禁止：

-   修改workflow
-   添加特殊Draw.io逻辑
-   根据关键词直接选模型
-   提交API Key
-   合并Provider和Model Catalog

完成后输出：

-   修改文件
-   新增文件
-   架构变化
-   测试结果
-   风险
-   回滚方式

------------------------------------------------------------------------

# 八、最终目标

最终调用链：

    User Request
    
    ↓
    
    Agent Workflow
    
    ↓
    
    CustomConfigPlugin
    
    ↓
    
    RoutingContext
    
    ↓
    
    RoutingRequirement
    
    ↓
    
    Model Catalog
    
    ↓
    
    Constraint Filter
    
    ↓
    
    Dynamic Rank
    
    ↓
    
    Routing Decision
    
    ↓
    
    Provider Resolver
    
    ↓
    
    LLM

最终系统能力：

任何Agent：

↓

理解任务需求

↓

匹配模型能力

↓

结合实时状态

↓

动态选择最佳模型。

这才是真正的 Agent Model Router。
