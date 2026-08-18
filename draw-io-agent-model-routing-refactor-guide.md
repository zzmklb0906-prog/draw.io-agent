# draw.io-agent 模型路由重构与动态模型选择实施指南

> 适用仓库：`zzmklb0906-prog/draw.io-agent`  
> 基线分支：`main`  
> 基线提交：`a3bad9ae5cbb931666f72e4deaaf38a50e464f11`  
> 文档目的：供开发者或编码 Agent 按阶段实施模型路由重构；本文不是一次性“大改”的要求，而是一份可拆分、可验收、可回滚的工程执行计划。

---

## 0. 结论先行

当前项目已经具备：

- 多 Provider 接入能力；
- Qwen / DeepSeek / GLM / Kimi 等 Provider 映射配置；
- ADK `beforeModelCallback` 级别的统一模型切换入口；
- 模型路由 Trace；
- Agent Eval 平台；
- Invocation / Agent / Model / Tool 运行观测；
- 用户显式模型覆盖能力。

但是当前自动模型选择的核心仍然是：

```text
请求
  ↓
规则判断复杂度 L1 / L2 / L3
  ↓
fast-model / balanced-model / reasoning-model
  ↓
再根据模型名匹配 Provider
```

也就是说，项目当前更准确地属于：

> **Tier-based Model Routing（基于人工档位的三级模型路由）**

而不是：

> **Capability-aware Dynamic Model Routing（基于能力、成本、延迟、Agent 约束和运行状态的动态多模型路由）**

本次重构的最终目标不是“把 L1/L2/L3 判断调得更准”，而是将核心决策问题改造成：

```text
当前请求需要什么能力？
        +
当前 Agent 需要什么能力？
        +
当前上下文与输出有哪些硬约束？
        +
哪些模型当前可用？
        +
这些候选模型在质量 / 成本 / 延迟 / 稳定性上的综合收益如何？
        ↓
过滤不合格模型
        ↓
对所有合格候选模型打分排序
        ↓
选择最佳模型 + 备用模型
```

L1/L2/L3 可以继续保留，但只能作为**可解释性展示字段或粗粒度复杂度标签**，不能再直接决定某个固定模型。

---

# 1. 当前仓库真实结构与调用链

## 1.1 与模型路由直接相关的核心文件

当前路由代码主要位于：

```text
ai-agent-scaffold-draw-io-domain/
└── src/main/java/cn/bugstack/ai/domain/agent/service/llm/
    ├── ModelRoutingService.java
    ├── OpenAiCompatibleLlm.java
    ├── provider/
    │   ├── ModelProviderProperties.java
    │   └── ModelProviderRegistryService.java
    └── strategy/
        ├── IModelRouterStrategy.java
        ├── CompositeModelRouter.java
        ├── SemanticVectorModelRouter.java
        ├── LlmClassifierModelRouter.java
        └── RuleBasedModelRouter.java
```

集成点：

```text
ai-agent-scaffold-draw-io-domain/
└── .../service/armory/matter/plugin/CustomConfigPlugin.java
```

配置：

```text
ai-agent-scaffold-draw-io-app/
└── src/main/resources/application-dev.yml

api-model.md
```

测试：

```text
ai-agent-scaffold-draw-io-app/
└── src/test/java/cn/bugstack/ai/test/domain/agent/ModelRoutingServiceTest.java
```

前端路由 Trace：

```text
front/src/features/monitor/ModelRoutingTrace.tsx
```

---

## 1.2 当前真实调用链

当前关键调用关系可以概括为：

```text
ADK Agent 即将调用模型
        ↓
CustomConfigPlugin.beforeModelCallback(...)
        ↓
检查用户是否显式指定模型
        │
        ├─ 是 → 使用用户指定模型
        │
        └─ 否
             ↓
       ModelRoutingService.route(request)
             ↓
       CompositeModelRouter
             ↓
    SemanticVectorModelRouter
          ├─ L1/L3 → 直接返回
          └─ L2
               ↓
       LlmClassifierModelRouter
          ├─ L1/L3 → 直接返回
          └─ L2
               ↓
       RuleBasedModelRouter
             ↓
 fast / balanced / reasoning 三选一
             ↓
 ModelProviderRegistryService
 根据最终模型名称匹配 Provider
             ↓
 OpenAiCompatibleLlm
 发起实际 API 请求
```

这个调用入口非常适合继续保留。

**本次不建议推翻 ADK Plugin 层。**

最合理的做法是：

```text
CustomConfigPlugin
```

继续作为统一的“模型调用前路由入口”，只把：

```java
modelRoutingService.route(request)
```

逐渐升级为：

```java
modelRoutingService.route(routingContext)
```

---

# 2. 当前实现存在的主要工程问题

## 2.1 自动路由实际上只在三个固定槽位中选择

当前 `ModelRoutingService` 的核心配置是：

```text
fastModel
balancedModel
reasoningModel
```

配置文件又进一步把这三个槽位映射为三个固定模型。

因此即使系统已经配置：

- Qwen；
- DeepSeek；
- GLM；
- Kimi；

当前自动路由也不会把所有已注册模型作为候选集进行比较。

### 必须改变的抽象

从：

```text
请求 → L1/L2/L3 → 固定模型
```

变成：

```text
请求 → RoutingRequirement
      ↓
ModelCatalog.getEnabledModels()
      ↓
HardConstraintFilter
      ↓
ModelRanker
      ↓
Top-1 + Backups
```

---

## 2.2 `SemanticVectorModelRouter` 不是语义向量路由

当前实现主要是：

```java
String text = String.valueOf(request.contents());
```

然后：

```java
if (text.contains(keyword)) {
    accumulatedWeight += weight;
}
```

再经过指数饱和、文本长度因子等公式计算分数。

它没有真实执行：

- Embedding；
- Vector；
- Cosine Similarity；
- Semantic Prototype；
- 标注样本近邻检索。

### 第一阶段处理原则

不要马上接 Embedding。

先把这个类的“职责”纠正。

建议两种方案：

**兼容性方案：**

暂时保留类名，但增加：

```java
@Deprecated
```

并明确注释：

```text
Legacy keyword-density heuristic.
Not a real embedding/vector semantic router.
```

**重构方案：**

改名为：

```text
KeywordDensityFeatureExtractor
```

不要再让它直接选择模型。

---

## 2.3 `LlmClassifierModelRouter` 当前没有调用 LLM/SLM

`evaluateWithSlm()` 当前仍然是：

- 字符长度；
- complex keyword；
- simple keyword；
- if/else。

因此它并不是一个真正的 SLM classifier。

建议最终改造成：

```text
HeuristicComplexityFeatureExtractor
```

以后真的接入小模型时，再新建：

```text
SlmRoutingFeatureExtractor
```

### 重要原则

未来 SLM 不应该直接输出：

```json
{"model": "qwen3.8-max"}
```

而应该输出：

```json
{
  "taskType": "DRAWIO_GENERATION",
  "reasoningRequired": 62,
  "structuredOutputRequired": 95,
  "toolCallingRequired": 75,
  "contextDependency": 40
}
```

“选择哪个模型”仍然由 Router 完成。

---

## 2.4 Composite 当前是短路责任链，而不是多策略融合

当前逻辑本质上：

```text
Semantic
   ↓
L1/L3 就结束

Classifier
   ↓
L1/L3 就结束

Rule
```

这导致后续策略几乎无法修正前一层误判。

最终应变成：

```text
              Request
                 │
       ┌─────────┼─────────┐
       ↓         ↓         ↓
   Heuristic  Semantic    SLM
       │         │         │
       └─────────┼─────────┘
                 ↓
         FeatureEvidence
                 ↓
       EvidenceAggregator
                 ↓
       RoutingRequirement
```

注意：

**这是 V8 的最终目标。**

V1～V5 不需要马上接真正 Semantic / SLM。

---

## 2.5 当前路由会受到历史消息污染

`String.valueOf(request.contents())` 很可能把整个 LLM Request 中的多轮内容都作为任务文本。

对于模型路由：

```text
任务复杂度判断
```

和：

```text
上下文窗口需求判断
```

必须分离。

例如：

```text
latestUserMessage
```

主要用于：

- 当前动作；
- 当前意图；
- 当前复杂度；
- 当前输出类型。

而：

```text
wholeContext
```

只应该主要用于：

- context token estimate；
- 是否需要长上下文模型；
- 是否依赖历史状态。

---

# 3. 重构后的总体目标架构

最终建议架构：

```text
                    CustomConfigPlugin
                           │
                  explicit model?
                    │            │
                   YES          NO
                    │            │
                    │      RoutingContextFactory
                    │            │
                    │            ▼
                    │    RoutingRequirementAnalyzer
                    │            │
                    │            ▼
                    │       ModelCatalogService
                    │            │
                    │      all enabled models
                    │            │
                    │            ▼
                    │    ModelConstraintFilter
                    │            │
                    │      valid candidates
                    │            │
                    │            ▼
                    │        ModelRanker
                    │            │
                    │      ranked candidates
                    │            │
                    └────────────┤
                                 ▼
                         RoutingDecision
                       selected + backups
                                 │
                                 ▼
                    ModelProviderRegistryService
                                 │
                                 ▼
                     OpenAiCompatibleLlm
```

---

# 4. 建议的新代码结构

不要一次性移动现有全部类。

建议在现有：

```text
domain/agent/service/llm/
```

内部逐步增加：

```text
llm/
├── ModelRoutingService.java
├── OpenAiCompatibleLlm.java
│
├── catalog/
│   ├── ModelCatalogProperties.java
│   ├── ModelCatalogService.java
│   ├── ModelProfile.java
│   ├── ModelCapabilities.java
│   ├── ModelLimits.java
│   └── ModelPricing.java
│
├── routing/
│   ├── RoutingContext.java
│   ├── RoutingDecision.java
│   ├── RoutingRequirement.java
│   ├── ModelCandidate.java
│   │
│   ├── extract/
│   │   ├── LatestUserMessageExtractor.java
│   │   ├── RequestFeatureExtractor.java
│   │   └── HeuristicFeatureExtractor.java
│   │
│   ├── filter/
│   │   └── ModelConstraintFilter.java
│   │
│   ├── score/
│   │   ├── ModelScorer.java
│   │   └── WeightedModelScorer.java
│   │
│   └── policy/
│       ├── AgentRoutingPolicy.java
│       └── AgentRoutingPolicyRegistry.java
│
├── provider/
│   ├── ModelProviderProperties.java
│   └── ModelProviderRegistryService.java
│
└── strategy/
    └── legacy...
```

注意：

`ModelProviderRegistryService` **不要删除或改造成 ModelCatalog**。

二者职责不同：

```text
ModelCatalogService
= 这个模型有什么能力？

ModelProviderRegistryService
= 这个模型应该去哪个 API Endpoint？
```

---

# 5. 分阶段实施计划

下面各阶段建议分别提交，禁止一个 PR 同时完成所有阶段。

---

# Phase 0：建立安全基线与回归基线

## 目标

在改变路由行为之前，先形成可验证基线。

## 必须处理

### 0.1 凭证清理

仓库当前配置与模型说明文档中存在不应进入版本库的凭证类信息。

编码 Agent 必须：

1. 不把任何现有 Key 复制到新文件；
2. 将真实凭证全部改成环境变量引用；
3. 对已经暴露过的凭证执行轮换；
4. 检查：
   - `api-model.md`
   - `application-dev.yml`
   - 其他 YAML / `.env` / docs；
5. 保留 `.env.example`，但只允许占位符；
6. 如凭证已经进入 Git 历史，按项目需要决定是否清理历史。

### 0.2 新增路由回归测试集

现有 3 个 happy-path 测试继续保留，但至少补充：

```text
1. 不需要进行架构分析，只修改标题
2. 把这个架构图标题改成系统架构
3. 总结一篇非常长的文章
4. 解释 ABA 问题
5. 分析 Java 并发死锁
6. 超长 Draw.io XML 只做格式整理
7. 中英文混合任务
8. 否定表达
9. 空请求
10. 多轮历史：第一轮复杂、第二轮只改标题
11. 用户显式模型覆盖
12. Provider 配置找不到
13. 路由关闭
14. 模型名称为空
15. agent_analyst 与 agent_drawer 对同一请求的需求差异
```

## 验收标准

- 当前旧路由行为可复现；
- 新增反例测试允许先标注为“expected future behavior”或单独测试 Feature Extractor；
- 不因为 Phase 0 改变现有生产路由行为。

---

# Phase 1：先修正“输入”和“命名”，暂不做动态多模型

## 目标

解决最明显的误路由来源，同时控制改动范围。

## 1.1 新建 LatestUserMessageExtractor

示例：

```java
package cn.bugstack.ai.domain.agent.service.llm.routing.extract;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LatestUserMessageExtractor {

    public String extract(LlmRequest request) {
        if (request == null || request.contents() == null || request.contents().isEmpty()) {
            return "";
        }

        List<Content> contents = request.contents();

        for (int i = contents.size() - 1; i >= 0; i--) {
            Content content = contents.get(i);

            // 具体 role / parts API 以当前 ADK 1.7 实际类型为准。
            // 编码 Agent 必须先检查 Content 的真实 API，不能机械复制本示例。
            if (isUserContent(content)) {
                return extractText(content);
            }
        }

        return "";
    }

    private boolean isUserContent(Content content) {
        return content.role()
                .map(role -> "user".equalsIgnoreCase(role))
                .orElse(false);
    }

    private String extractText(Content content) {
        return content.parts()
                .orElse(List.of())
                .stream()
                .map(Part::text)
                .flatMap(java.util.Optional::stream)
                .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);
    }
}
```

> 上面代码是结构示例。编码 Agent 必须根据仓库实际使用的 Google GenAI / ADK 版本确认 `role()`、`parts()`、`Part.text()` 签名后再提交。

---

## 1.2 旧 Router 暂时继续运行，但输入改成 latest user text

不要再让各 Router 自己：

```java
String.valueOf(request.contents())
```

建议先统一生成：

```java
RoutingTextInput
```

例如：

```java
public record RoutingTextInput(
        String latestUserText,
        int totalContextChars
) {}
```

这样：

```text
latestUserText
```

负责“意图复杂度”，

```text
totalContextChars
```

只负责“上下文需求”。

---

## 1.3 修改可观测性文案

前端当前存在：

```text
收起思考
决策思考与推导
真实量化指标
```

这些名称容易使用户误认为展示的是模型内部推理。

建议改成：

```text
收起依据
路由决策摘要
路由量化特征
候选模型评分
过滤原因
```

后端 narrative 同样只描述可验证规则：

```text
“检测到结构化输出需求，因此提高 structuredOutputRequired”
```

不要描述：

```text
“模型经过深度思考认为……”
```

更不要写：

```text
100% 正确
```

---

## Phase 1 验收

必须新增测试：

```java
@Test
void historyShouldNotEscalateCurrentSimpleEdit() {
    // 第一轮复杂架构分析
    // 第二轮只改标题
    // 复杂度分析必须主要由最后一条 user message 决定
}
```

---

# Phase 2：建立 Model Catalog，让所有模型真正进入候选池

## 目标

把：

```text
fast / balanced / reasoning
```

从“模型集合”降级为 legacy fallback。

建立真正的：

```text
N models
```

候选池。

---

## 2.1 新建 ModelProfile

示例：

```java
public record ModelProfile(
        String id,
        String provider,
        String modelName,
        boolean enabled,
        ModelCapabilities capabilities,
        ModelLimits limits,
        ModelPricing pricing
) {}
```

```java
public record ModelCapabilities(
        int reasoning,
        int instructionFollowing,
        int coding,
        int structuredOutput,
        int toolCalling,
        int vision,
        int longContext
) {}
```

```java
public record ModelLimits(
        long contextWindow,
        long maxOutputTokens
) {}
```

```java
public record ModelPricing(
        double inputPerMillion,
        double outputPerMillion
) {}
```

### 关于 0～100 能力分

初期允许人工配置：

```text
reasoning = 80
structuredOutput = 90
```

但必须明确：

> 这些数字是“Routing Calibration Score”，不是模型厂商官方能力分。

以后用 Eval 数据校准。

---

## 2.2 新增独立配置

不要继续：

```yaml
fast-model:
balanced-model:
reasoning-model:
```

作为主配置。

目标配置示例：

```yaml
ai:
  agent:
    model-routing:
      enabled: true
      mode: dynamic
      legacy-fallback-model: qwen3.7-plus

      weights:
        capability: 0.45
        agent-fit: 0.20
        reliability: 0.15
        latency: 0.10
        cost: 0.10

    model-catalog:
      qwen3.7-flash:
        provider: qwen
        model-name: qwen3.7-flash
        enabled: true

        capabilities:
          reasoning: 55
          instruction-following: 82
          coding: 65
          structured-output: 80
          tool-calling: 80
          vision: 0
          long-context: 90

        limits:
          context-window: 1000000
          max-output-tokens: 65536

        pricing:
          input-per-million: 0
          output-per-million: 0
```

注意：

- 示例中的能力分必须由项目 Eval 校准；
- 价格必须从 `api-model.md` 中整理；
- 不要把 API Key 放进 `model-catalog`；
- Provider Credential 继续走环境变量。

---

## 2.3 新建 ModelCatalogService

示例：

```java
@Service
public class ModelCatalogService {

    private final ModelCatalogProperties properties;

    public ModelCatalogService(ModelCatalogProperties properties) {
        this.properties = properties;
    }

    public List<ModelProfile> getEnabledModels() {
        return properties.getModels()
                .values()
                .stream()
                .filter(ModelProfile::enabled)
                .toList();
    }

    public Optional<ModelProfile> get(String modelId) {
        return Optional.ofNullable(
                properties.getModels().get(modelId)
        );
    }
}
```

---

## 2.4 启动时做 Catalog Validation

至少校验：

```text
modelId 不重复
provider 必须存在
contextWindow > 0
maxOutputTokens > 0
capability score ∈ [0,100]
price >= 0
enabled model 必须能解析 Provider
```

### 关键设计

Provider Registry 和 Catalog 的关系：

```text
ModelCatalog
    qwen3.7-plus
       provider = qwen
       capabilities = ...
       limits = ...

ProviderRegistry
    qwen
       baseUrl = ...
       apiKey = env
       completionsPath = ...
```

推荐最终从：

```text
modelName regex → provider
```

逐步升级到：

```text
modelProfile.provider → providerConfig
```

regex 可以保留作为兼容 fallback。

---

# Phase 3：从“复杂度等级”升级为 RoutingRequirement

## 目标

不再直接回答：

```text
L1 / L2 / L3
```

而是回答：

```text
这个任务需要什么？
```

---

## 3.1 RoutingContext

推荐：

```java
public record RoutingContext(
        LlmRequest request,
        String latestUserText,
        String agentName,
        String workflowStage,
        long estimatedContextTokens,
        boolean explicitModel,
        String explicitModelName
) {}
```

`CustomConfigPlugin` 当前已经可以拿到：

```text
activeAgentName(invocationId)
```

因此应在这里注入 `agentName`。

---

## 3.2 RoutingRequirement

建议：

```java
public record RoutingRequirement(
        TaskType taskType,
        int reasoningRequired,
        int instructionFollowingRequired,
        int codingRequired,
        int structuredOutputRequired,
        int toolCallingRequired,
        boolean visionRequired,
        long minContextWindow,
        long expectedOutputTokens,
        String agentName,
        Map<String, Object> evidence
) {}
```

任务类型：

```java
public enum TaskType {
    SIMPLE_EDIT,
    FORMAT,
    SUMMARIZE,
    EXTRACT,
    GENERAL_CHAT,
    ANALYZE,
    DIAGNOSE,
    PLAN,
    CODE_GENERATION,
    CODE_ANALYSIS,
    DRAWIO_GENERATION,
    STRUCTURED_GENERATION,
    TOOL_ORCHESTRATION
}
```

---

## 3.3 第一版 Feature Extractor 仍然可以用规则

重要变化不是“完全消灭规则”。

而是：

### 旧逻辑

```text
发现“架构”
→ L3
→ reasoning-model
```

### 新逻辑

```text
发现“架构”
→ domain = architecture

发现“分析”
→ reasoning + 20

发现“只修改标题”
→ scope = local-edit
→ reasoning - 25

当前 Agent = agent_drawer
→ structuredOutput + 30
→ toolCalling + 20
```

示例：

```java
@Component
public class HeuristicFeatureExtractor implements RequestFeatureExtractor {

    @Override
    public RequestFeatures extract(RoutingContext context) {
        String text = context.latestUserText();

        int reasoning = 20;
        int coding = 0;
        int structured = 0;
        int tools = 0;

        if (containsAny(text, "分析", "诊断", "比较", "推导", "规划", "设计")) {
            reasoning += 25;
        }

        if (containsAny(text, "并发", "死锁", "一致性", "状态机")) {
            reasoning += 20;
        }

        if (containsAny(text, "Java", "Python", "SQL", "代码", "Spring")) {
            coding += 30;
        }

        if (containsAny(text, "JSON", "XML", "Draw.io", "结构化输出")) {
            structured += 30;
        }

        if (containsAny(text, "只修改", "仅修改", "改标题", "改名字", "不要分析")) {
            reasoning -= 25;
        }

        if ("agent_drawer".equals(context.agentName())) {
            structured += 35;
            tools += 20;
        }

        return new RequestFeatures(
                clamp(reasoning),
                clamp(coding),
                clamp(structured),
                clamp(tools)
        );
    }
}
```

---

## 3.4 文本长度降为辅助特征

不要：

```text
文本越长 → 越强模型
```

建议：

```text
文本长度只影响：
1. contextWindow hard constraint
2. 最多 5%～10% 的复杂度辅助特征
```

特别是：

```text
长文摘要
```

应优先需要：

```text
longContext
```

不一定需要：

```text
highReasoning
```

---

# Phase 4：Hard Constraint Filter

这是从“演示路由”走向工程路由最关键的一步。

## 原则

**先过滤，再评分。**

任何不满足硬约束的模型，不允许参与最终评分竞争。

---

## 4.1 建议硬约束

至少：

```text
enabled
providerAvailable
contextWindow >= requestContext + outputReserve
supportsVision when image required
supportsToolCalling when tools are mandatory
structuredOutput ability meets hard minimum
maxOutputTokens >= expected output
```

---

## 4.2 示例

```java
@Component
public class ModelConstraintFilter {

    public FilterResult filter(
            List<ModelProfile> models,
            RoutingRequirement requirement) {

        List<ModelProfile> accepted = new ArrayList<>();
        Map<String, List<String>> rejected = new LinkedHashMap<>();

        for (ModelProfile model : models) {
            List<String> reasons = new ArrayList<>();

            if (!model.enabled()) {
                reasons.add("MODEL_DISABLED");
            }

            if (model.limits().contextWindow()
                    < requirement.minContextWindow()) {
                reasons.add("CONTEXT_WINDOW_TOO_SMALL");
            }

            if (requirement.visionRequired()
                    && model.capabilities().vision() <= 0) {
                reasons.add("VISION_NOT_SUPPORTED");
            }

            if (model.limits().maxOutputTokens()
                    < requirement.expectedOutputTokens()) {
                reasons.add("OUTPUT_LIMIT_TOO_SMALL");
            }

            if (reasons.isEmpty()) {
                accepted.add(model);
            } else {
                rejected.put(model.id(), reasons);
            }
        }

        return new FilterResult(accepted, rejected);
    }
}
```

---

## 4.3 Draw.io Agent 的硬约束示例

### `agent_analyst`

重点：

```text
instruction following
JSON structured output
moderate reasoning
```

### `agent_drawer`

重点：

```text
structured output
XML stability
tool calling
instruction following
sufficient output tokens
```

### `agent_reviewer`

重点：

```text
XML parsing / validation
structured output
instruction following
```

注意：

当前仓库中 reviewer 虽已定义，但目前 sequential workflow 只装配了 analyst + drawer。

**路由重构任务不得顺手改变 workflow。**

如要启用 reviewer：

```text
单独 Issue / PR
```

并通过 Eval 验证。

---

# Phase 5：Dynamic Model Scoring

## 目标

所有通过 Hard Filter 的模型都参与排名。

---

## 5.1 ModelCandidate

```java
public record ModelCandidate(
        ModelProfile model,
        double totalScore,
        Map<String, Double> scoreBreakdown
) {}
```

---

## 5.2 初版评分维度

建议先做：

```text
capability fit   45%
agent fit        20%
reliability      15%
latency          10%
cost             10%
```

权重必须配置化。

不要硬编码为永远不变。

---

## 5.3 不要让“最强模型”天然稳赢

如果只写：

```java
Math.min(capability / requirement, 1.0)
```

那么所有超过要求的强模型都拿 1 分。

随后如果价格和延迟数据不完善，就会持续偏向大模型。

建议对：

```text
能力不足
```

进行明显处罚，

对：

```text
过度能力
```

进行轻微浪费处罚。

示意：

```java
private double fit(int capability, int requirement) {
    if (requirement <= 0) {
        return 1.0;
    }

    double deficit =
            Math.max(0, requirement - capability) / 100.0;

    double overProvision =
            Math.max(0, capability - requirement) / 100.0;

    return clamp01(
            1.0
            - deficit * 1.5
            - overProvision * 0.15
    );
}
```

这不是最终公式，而是 V1 可解释基线。

最终应该通过 Eval 数据调参。

---

## 5.4 WeightedModelScorer 示例

```java
@Component
public class WeightedModelScorer {

    public ModelCandidate score(
            ModelProfile model,
            RoutingRequirement req,
            RoutingRuntimeMetrics runtime,
            RoutingWeights weights) {

        double reasoningFit =
                fit(model.capabilities().reasoning(),
                    req.reasoningRequired());

        double structuredFit =
                fit(model.capabilities().structuredOutput(),
                    req.structuredOutputRequired());

        double toolFit =
                fit(model.capabilities().toolCalling(),
                    req.toolCallingRequired());

        double capabilityScore =
                reasoningFit * 0.40
                + structuredFit * 0.35
                + toolFit * 0.25;

        double reliability =
                runtime.successRate();

        double latency =
                runtime.latencyScore();

        double cost =
                runtime.costScore();

        double total =
                capabilityScore * weights.capability()
                + agentFit(model, req) * weights.agentFit()
                + reliability * weights.reliability()
                + latency * weights.latency()
                + cost * weights.cost();

        return new ModelCandidate(
                model,
                total,
                Map.of(
                        "capability", capabilityScore,
                        "reliability", reliability,
                        "latency", latency,
                        "cost", cost
                )
        );
    }
}
```

---

# Phase 6：Agent-aware Routing

这是本项目非常适合做成亮点的一层。

当前 Draw.io Agent 的实际角色包括：

```text
agent_analyst
agent_drawer
agent_reviewer
```

不能对三个 Agent 使用完全相同的路由需求。

---

## 6.1 AgentRoutingPolicy

```java
public record AgentRoutingPolicy(
        String agentName,
        int reasoningBoost,
        int structuredOutputBoost,
        int toolCallingBoost,
        int instructionFollowingBoost,
        int codingBoost
) {}
```

---

## 6.2 初始策略建议

### agent_analyst

职责：

```text
自然语言 → 可审核 JSON / Brief
```

更关注：

```text
instruction following
structured JSON
moderate reasoning
```

不要天然使用最大推理模型。

---

### agent_drawer

职责：

```text
Approved Brief
→ nodes / edges / drawio_done
→ Draw.io XML
```

更关注：

```text
structured output
XML consistency
tool calling
output length
instruction following
```

这里尤其要注意：

> “更强推理模型”不等于“XML 合法率更高”。

因此模型实际 Eval 的：

```text
XML parse pass rate
edge reference validity
duplicate node rate
drawio_done completeness
```

应该逐渐进入 reliability / agent-fit。

---

### agent_reviewer

职责：

```text
检查与修正 XML / layout
```

更关注：

```text
structured validation
coding-like syntax checking
instruction following
```

但在当前 workflow 状态下先只配置策略，不强行启用该 Agent。

---

# Phase 7：Fallback 与 Provider Health

## 目标

Router 不只返回一个模型。

建议：

```java
public record RoutingDecision(
        String selectedModel,
        List<ModelCandidate> rankedCandidates,
        RoutingRequirement requirement,
        Map<String, List<String>> filteredModels,
        String routingVersion,
        String reason
) {}
```

---

## 7.1 Top-N fallback

例如：

```text
1. qwen3.7-plus
2. deepseek-v4-flash
3. qwen3.8-max
```

Provider 调用失败时：

```text
NETWORK_ERROR
RATE_LIMIT
PROVIDER_UNAVAILABLE
```

可以尝试下一候选。

但必须区分：

```text
Routing Decision
```

和：

```text
Runtime Retry / Failover
```

不要把失败重试重新包装成一次“智能判断”。

---

## 7.2 ProviderHealthService

建议逐步新增：

```java
public interface ProviderHealthService {

    ProviderHealth get(String provider);
}
```

指标：

```text
available
recentSuccessRate
p50Latency
p95Latency
rateLimited
lastFailureAt
```

第一版可以：

```text
静态 available = true
```

之后再连接现有 Runtime Observation 数据。

---

# Phase 8：复用现有 Eval 与 Runtime Observation 做闭环

当前项目已经有：

```text
Agent Eval
Eval Dataset
Eval Case
Eval Run
Baseline
Invocation Trace
Model Call duration
input/output tokens
```

因此不要另建一套“路由训练数据库”。

建议新增路由评测数据：

```text
routing selected model
candidate ranking
score breakdown
filter reasons
agent name
task type
structured-output pass/fail
retry count
model latency
input/output tokens
final eval score
```

---

## 8.1 离线校准优先

不要第一版就做：

```text
在线自学习
```

建议：

```text
Production Trace
       ↓
脱敏汇总
       ↓
Eval Dataset
       ↓
不同模型重复执行
       ↓
比较质量 / latency / token / cost
       ↓
人工更新 capability / weights
```

这样：

- 可解释；
- 可回滚；
- 不会因为少量噪声把路由策略带偏。

---

## 8.2 为模型路由建立专门 Eval Case

至少：

```text
drawio/simple-flowchart
drawio/complex-architecture
drawio/large-xml-edit
drawio/negation-simple-edit
analyst/ambiguous-requirement
analyst/confirmed-brief
drawer/strict-xml
drawer/tool-required
general/long-summary
code/concurrency-diagnosis
```

每个 Case 对不同模型重复运行。

---

## 8.3 关键 Agent 指标

### agent_analyst

```text
JSON parse rate
required field completeness
question correctness
instruction adherence
```

### agent_drawer

```text
NDJSON parse rate
XML parse rate
duplicate node/edge rate
invalid source/target count
drawio_done completeness
tool precision
latency
tokens
```

### reviewer（启用后）

```text
修复成功率
误修改率
XML validity after review
额外 token 成本
```

---

# Phase 9：真正的 Semantic Router / SLM Router（可选增强）

只有 V1～V8 稳定后再做。

---

## 9.1 真 Semantic Feature Extractor

可以：

```text
latest user request
        ↓
embedding
        ↓
与标注任务原型计算 cosine similarity
        ↓
task / complexity evidence
```

例如准备：

```text
simple_edit_examples
summarization_examples
architecture_reasoning_examples
drawio_generation_examples
code_debug_examples
```

输出仍然是：

```text
FeatureEvidence
```

不是模型名。

---

## 9.2 真 SLM Classifier

SLM Prompt 要求严格 JSON：

```json
{
  "taskType": "DRAWIO_GENERATION",
  "reasoningRequired": 65,
  "codingRequired": 20,
  "structuredOutputRequired": 95,
  "toolCallingRequired": 75,
  "contextDependency": 35,
  "confidence": 0.88
}
```

### 超时策略

SLM classifier：

```text
超时 / JSON 非法 / Provider 错误
```

必须：

```text
fallback → heuristic extractor
```

不能阻断用户请求。

---

## 9.3 新 Composite 定义

最终 Composite 应该是：

```text
Rule Evidence
   +
Embedding Evidence
   +
SLM Evidence
   ↓
EvidenceAggregator
   ↓
RoutingRequirement
```

不再是：

```text
第一层命中就 return
```

---

# 6. 建议的新 ModelRoutingService

最终形态示意：

```java
@Service
public class ModelRoutingService {

    private final RoutingContextFactory contextFactory;
    private final RoutingRequirementAnalyzer requirementAnalyzer;
    private final ModelCatalogService modelCatalog;
    private final ModelConstraintFilter constraintFilter;
    private final ModelRanker modelRanker;

    public RoutingDecision route(
            LlmRequest request,
            String agentName) {

        RoutingContext context =
                contextFactory.create(
                        request,
                        agentName
                );

        RoutingRequirement requirement =
                requirementAnalyzer.analyze(context);

        List<ModelProfile> allModels =
                modelCatalog.getEnabledModels();

        FilterResult filterResult =
                constraintFilter.filter(
                        allModels,
                        requirement
                );

        if (filterResult.accepted().isEmpty()) {
            return fallbackDecision(
                    context,
                    requirement,
                    filterResult
            );
        }

        List<ModelCandidate> ranked =
                modelRanker.rank(
                        filterResult.accepted(),
                        requirement
                );

        return RoutingDecision.auto(
                ranked.get(0),
                ranked,
                requirement,
                filterResult.rejected()
        );
    }
}
```

对应 `CustomConfigPlugin`：

```java
String agentName =
        monitorService.activeAgentName(
                context.invocationId()
        );

RoutingDecision decision =
        modelRoutingService.route(
                requestBuilder.build(),
                agentName
        );
```

这样：

```text
同一个用户请求
```

在：

```text
agent_analyst
```

和：

```text
agent_drawer
```

阶段允许选择不同模型。

这非常符合 Multi-Agent 系统本身。

---

# 7. 兼容迁移策略

禁止一次性删除：

```text
CompositeModelRouter
SemanticVectorModelRouter
LlmClassifierModelRouter
RuleBasedModelRouter
```

建议：

## V1

```text
legacy strategy 继续可选
dynamic strategy 新增
```

配置：

```yaml
ai:
  agent:
    model-routing:
      mode: legacy
```

或：

```yaml
mode: dynamic
```

---

## V2

引入：

```text
shadow mode
```

真实请求仍使用 Legacy：

```text
Legacy → 真正执行
Dynamic → 只计算不执行
```

记录：

```text
legacyModel
dynamicModel
candidateScores
```

用 Eval 对比。

---

## V3

Dynamic 成为默认：

```yaml
mode: dynamic
```

Legacy 保留一段时间作为回滚。

---

## V4

确认稳定后删除旧三级 Router。

---

# 8. 测试设计

## 8.1 LatestUserMessageExtractorTest

必须覆盖：

```text
single user message
multi-turn
assistant history
empty content
mixed content parts
```

---

## 8.2 HeuristicFeatureExtractorTest

反例是重点：

```text
“不需要架构分析，只修改标题”
```

不应该因为“架构”成为高推理任务。

```text
“解释 ABA 问题”
```

即使很短，也应该有较高 reasoning requirement。

```text
“一万字文章做摘要”
```

应该主要提高 longContext，不应该因为长度自动升级 reasoning。

---

## 8.3 ModelConstraintFilterTest

```text
vision required → no-vision model rejected
context too small → rejected
disabled model → rejected
output limit too small → rejected
valid model → accepted
```

---

## 8.4 ModelRankerTest

必须验证：

```text
所有 accepted model 都有 score
score breakdown 总和可解释
排序稳定
不满足硬约束模型永远不会出现在 ranked list
```

---

## 8.5 Agent Policy Test

同一输入：

```text
“生成登录流程图”
```

分别传入：

```text
agent_analyst
agent_drawer
```

要求：

```text
RoutingRequirement 不同
```

而不是强制：

```text
selectedModel 必须不同
```

因为最终模型是否不同应该由实际能力与评分决定。

---

## 8.6 Integration Test

测试完整：

```text
CustomConfigPlugin
→ route
→ selected model
→ provider resolver
```

同时测试：

```text
用户显式模型
```

必须保持最高优先级兼容行为。

---

# 9. 可观测性应该升级成什么样

当前路由 Trace 建议最终显示：

```text
Agent
Task Type
Latest User Action
Estimated Context Tokens
Routing Requirements

Hard Filter:
  model A → accepted
  model B → rejected: NO_TOOL_CALLING
  model C → rejected: CONTEXT_TOO_SMALL

Candidate Ranking:
  #1 model-x 0.87
     capability 0.91
     agentFit   0.93
     reliability 0.84
     latency    0.76
     cost       0.81

  #2 model-y 0.83
  #3 model-z 0.79

Selected:
  model-x

Fallback:
  model-y, model-z
```

前端标题：

```text
模型路由决策
路由依据
候选模型评分
硬约束过滤
```

不要：

```text
思考过程
思维链
真实推理
```

---

# 10. 与现有 Agent Workflow 的结合

仓库当前 Draw.io 配置实际存在：

```text
agent_analyst
agent_drawer
agent_reviewer
```

但：

```text
sequential_draw_process
```

当前只列：

```text
agent_analyst
agent_drawer
```

因此需要单独创建一个架构检查项：

> “agent_reviewer 是预留但未启用，还是工作流漏接？”

不要在模型路由 PR 中顺手修改。

如果决定启用，单独做：

```text
Workflow PR
+
Eval regression
```

---

# 11. 不建议现在做的事情

## 11.1 不要立即上强化学习

当前数据量和标签还不足以支撑。

---

## 11.2 不要立即接 3 个额外 AI Router

不要：

```text
规则模型
+
Embedding
+
SLM
+
大模型 Judge
```

然后让一个用户请求在真正 Agent 执行前先消耗多次模型调用。

优先把：

```text
Model Catalog
Hard Filter
Scoring
Eval
```

做扎实。

---

## 11.3 不要把 api-model.md 直接当 Runtime Config 解析

建议：

```text
api-model.md
```

继续是人类说明文档。

Runtime 使用：

```text
model-catalog.yml
```

或 Spring ConfigurationProperties。

否则：

- Markdown 格式不稳定；
- 容易混入 Key；
- 不适合启动校验；
- 不适合版本化 schema。

---

## 11.4 不要把 Provider Registry 和 Model Catalog 合并

再次强调：

```text
Provider
= 怎么连接

Model Profile
= 模型能做什么

Router
= 为什么选它
```

保持三者独立。

---

# 12. 推荐 PR / Agent 下发顺序

建议按下面顺序逐个下发。

---

## Task 01 — Routing Baseline & Regression Tests

### 读取

```text
ModelRoutingService.java
CompositeModelRouter.java
SemanticVectorModelRouter.java
LlmClassifierModelRouter.java
RuleBasedModelRouter.java
ModelRoutingServiceTest.java
CustomConfigPlugin.java
```

### 完成

- 增加反例测试；
- 不改变现有生产行为；
- 输出现有路由行为表。

---

## Task 02 — Latest User Message Routing Input

### 完成

- 新建 `LatestUserMessageExtractor`；
- 旧 Router 不再用全量 contents 作为主要复杂度文本；
- whole context 长度单独计算；
- 测试历史污染。

---

## Task 03 — Routing Terminology Cleanup

### 完成

- 前端“思考”改“路由依据”；
- 后端移除 100% 正确等表述；
- 对 legacy semantic / slm 类增加准确注释或逐步重命名；
- 不改变选模行为。

---

## Task 04 — Model Catalog

### 读取

```text
api-model.md
application-dev.yml
ModelProviderProperties.java
ModelProviderRegistryService.java
```

### 完成

- `ModelProfile`
- `ModelCapabilities`
- `ModelLimits`
- `ModelPricing`
- `ModelCatalogProperties`
- `ModelCatalogService`
- catalog validation
- 将当前实际计划使用的全部模型录入 catalog
- 禁止复制任何凭证

---

## Task 05 — RoutingContext & Requirement

### 完成

- `RoutingContext`
- `RoutingRequirement`
- `RequestFeatures`
- `TaskType`
- `HeuristicFeatureExtractor`
- agent name 传入
- length 降为辅助因素

---

## Task 06 — Hard Constraint Filter

### 完成

- `ModelConstraintFilter`
- reject reason
- candidate list
- context / vision / tool / output limits 等过滤
- 单元测试

---

## Task 07 — Dynamic Ranking

### 完成

- `ModelCandidate`
- `ModelScorer`
- `WeightedModelScorer`
- `ModelRanker`
- 配置化权重
- Top-N
- score breakdown

---

## Task 08 — Agent-aware Policy

### 完成

- `AgentRoutingPolicy`
- `AgentRoutingPolicyRegistry`
- analyst policy
- drawer policy
- reviewer policy（仅预留）
- 同任务不同 Agent requirement 测试

---

## Task 09 — Shadow Mode

### 完成

```text
legacy executes
dynamic observes
```

Trace 同时保存：

```text
legacy model
dynamic model
candidate ranking
```

不影响用户实际请求。

---

## Task 10 — Eval Calibration

### 完成

- 建 routing dataset；
- 每种关键任务在多个模型上重复跑；
- 收集质量 / tokens / latency；
- 校准 model profile；
- 建 baseline；
- 输出 regression 报告。

---

## Task 11 — Dynamic Default

### 完成

- `mode=dynamic`
- legacy fallback
- provider failover
- rollback switch

---

## Task 12 — Real Semantic / SLM（后续增强）

只有前面完成后再做。

---

# 13. 给编码 Agent 的总 Prompt

下面这段可以直接作为每个阶段的总约束附加给编码 Agent：

```text
你正在修改仓库 zzmklb0906-prog/draw.io-agent。

本次任务必须严格限定在当前指定 Phase，不要顺手重构无关模块。

开始编码前必须：
1. 阅读本 Phase 指定的现有源码；
2. 确认 main 最新代码与本文基线是否发生变化；
3. 输出你理解到的现有调用链；
4. 再开始修改。

工程原则：
- 保留 CustomConfigPlugin 作为 ADK beforeModelCallback 的统一模型路由入口；
- 用户显式模型覆盖优先级不得被破坏；
- ModelProviderRegistryService 负责 Provider 连接，不得与 ModelCatalogService 混为一体；
- 不允许把任何 API Key、数据库密码、JWT Secret 写入新代码或新文档；
- 不允许通过单个关键词直接指定某个最终模型；
- L1/L2/L3 如保留，只能作为解释字段，不能作为动态路由核心抽象；
- 新 Router 必须先执行 Hard Constraint Filter，再执行 Model Ranking；
- 不满足硬约束的模型不能因为总分高而重新进入候选集；
- 所有动态候选模型必须输出可解释 score breakdown；
- 不展示或伪装成模型 Chain-of-Thought，只展示系统可验证的路由依据；
- 不要在本模型路由任务中擅自修改 agent workflow；
- agent_reviewer 当前虽然存在，但是否接入 sequential workflow 属于独立任务；
- 优先复用现有 LightweightMonitorService、Agent Eval 与 Runtime Observation，不另造平行监控体系。

修改完成后必须输出：
1. 修改文件列表；
2. 新增文件列表；
3. 关键设计说明；
4. 新增测试列表；
5. 测试执行结果；
6. 未完成项；
7. 兼容性风险；
8. 回滚方式。

禁止只给“代码已完成”的结论。
```

---

# 14. Phase 完成定义（Definition of Done）

任何 Phase 都必须满足：

```text
[ ] 只修改当前 Phase 范围
[ ] 无明文凭证
[ ] 对旧行为的兼容边界明确
[ ] 新核心类有单元测试
[ ] 失败路径有测试
[ ] 不只测试 happy path
[ ] 路由结果有可解释字段
[ ] 日志不包含 API Key
[ ] 用户显式模型不被静默覆盖
[ ] Provider resolution 仍可正常工作
[ ] Maven 相关测试通过
[ ] 文档同步
```

Dynamic Router 上线前额外满足：

```text
[ ] 所有 enabled models 真正进入候选池
[ ] Hard Filter 可解释
[ ] 排名结果可解释
[ ] Agent-aware policy 生效
[ ] 有 fallback
[ ] 有 legacy rollback
[ ] 有 Eval baseline
[ ] Shadow 对比完成
```

---

# 15. 最终完成后的目标调用链

```text
User Request
     │
     ▼
ADK / Agent Workflow
     │
     ▼
CustomConfigPlugin
     │
     ├── User Explicit Model?
     │       └── YES → explicit model
     │
     └── NO
          │
          ▼
     RoutingContext
          │
          ├── latest user message
          ├── active agent
          ├── context tokens
          ├── output requirements
          └── workflow metadata
          │
          ▼
  Requirement Analyzer
          │
          ▼
   RoutingRequirement
          │
          ▼
     Model Catalog
          │
          ▼
  Hard Constraint Filter
          │
          ├── context
          ├── modality
          ├── tool calling
          ├── structured output
          ├── output limit
          └── provider health
          │
          ▼
      Model Ranker
          │
          ├── capability fit
          ├── agent fit
          ├── reliability
          ├── latency
          └── cost
          │
          ▼
   Ranked Candidates
          │
          ├── selected
          └── backups
          │
          ▼
 Provider Registry
          │
          ▼
 OpenAiCompatibleLlm
          │
          ▼
 Runtime Observation
          │
          ▼
      Agent Eval
          │
          ▼
 Offline Calibration
```

---

# 16. 最终项目定位

完成 Phase 0～5 后，可以准确描述为：

> 基于任务能力需求、上下文约束和模型能力画像实现多模型候选过滤与动态评分路由。

完成 Phase 6～8 后，可以进一步描述为：

> 面向 Multi-Agent Workflow 设计 Agent-aware 动态模型路由机制，综合结构化输出、Tool Calling、上下文窗口、质量、延迟与成本进行多目标选择，并利用 Eval / Runtime Trace 完成离线校准。

只有真正完成 Phase 9，并实际接入 Embedding / SLM 后，才建议使用：

> Semantic Routing / SLM-assisted Routing

这样的表述。

---

# 17. 本轮最优先实施顺序

如果只批准第一轮开发，建议严格只做：

```text
Phase 0
  ↓
Phase 1
  ↓
Phase 2
  ↓
Phase 3
  ↓
Phase 4
  ↓
Phase 5
```

即：

```text
测试基线
→ 当前用户消息提取
→ Model Catalog
→ Routing Requirement
→ Hard Filter
→ Dynamic Ranking
```

先不要做：

```text
Embedding
SLM
在线学习
强化学习
复杂 Bandit
```

原因不是这些技术没有价值，而是当前最大的结构性问题是：

> “模型候选池与模型选择逻辑没有解耦。”

把这层完成以后，后面的 Semantic / SLM / Feedback 才有一个正确的落点。

---

## 附：编码 Agent 第一单建议

第一单不要直接让 Agent “重写整个模型路由”。

建议下发：

```text
请执行《draw.io-agent 模型路由重构与动态模型选择实施指南》的 Phase 0 + Phase 1。

只允许：
1. 增加模型路由反例/边界测试；
2. 新增 LatestUserMessageExtractor；
3. 将路由复杂度判断从整个 request.contents() 改为主要分析最后一条 user message；
4. 保留 whole context 长度作为独立上下文指标；
5. 修正前端和 narrative 中“思考/推导/100%正确”等误导性表述。

禁止：
- 引入 Embedding；
- 引入 SLM；
- 增加新的模型 Catalog；
- 改变 Provider Registry；
- 修改 Agent Workflow；
- 删除旧 Router；
- 调整成新的 Dynamic Ranking。

完成后给出 diff、测试结果和下一阶段迁移风险。
```

这是风险最低、最适合开始的第一步。
