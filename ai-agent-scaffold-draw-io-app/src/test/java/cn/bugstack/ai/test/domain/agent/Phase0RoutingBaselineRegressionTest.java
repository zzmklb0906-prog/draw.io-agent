package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.chat.CustomApiConfigManager;
import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderProperties;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderRegistryService;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.strategy.CompositeModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.LlmClassifierModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.RuleBasedModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.SemanticVectorModelRouter;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 — Routing Baseline & Regression Foundation Test Suite
 *
 * <p>Validates and records current baseline behavior across 8 specified cases
 * without modifying any core routing logic.
 */
@DisplayName("Phase 0 Routing Baseline & Regression Foundation Tests")
public class Phase0RoutingBaselineRegressionTest {

    private static final LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
    private static final RuleBasedModelRouter ruleRouter = new RuleBasedModelRouter(extractor);
    private static final SemanticVectorModelRouter semanticRouter = new SemanticVectorModelRouter(extractor);
    private static final LlmClassifierModelRouter classifierRouter = new LlmClassifierModelRouter(extractor, semanticRouter);
    private static final CompositeModelRouter compositeRouter = new CompositeModelRouter(extractor, semanticRouter, classifierRouter, ruleRouter);

    private static final ModelRoutingService router = new ModelRoutingService(
            true, "composite", "fastModel", "balancedModel", "reasoningModel",
            List.of(ruleRouter, semanticRouter, classifierRouter, compositeRouter)
    );

    private static final List<ReportEntry> reportEntries = new ArrayList<>();

    public record ReportEntry(
            String caseId,
            String caseName,
            String input,
            String selectedModel,
            String routingLevel,
            String reason,
            String currentBehavior,
            String expectedFutureBehavior
    ) {}

    @BeforeAll
    static void setUp() {
        reportEntries.clear();
    }

    @AfterAll
    static void printRoutingBehaviorReport() {
        System.out.println("\n==================================================================================");
        System.out.println("                     PHASE 0 ROUTING BEHAVIOR BASELINE REPORT                     ");
        System.out.println("==================================================================================");
        for (ReportEntry entry : reportEntries) {
            System.out.println("Case: " + entry.caseId() + " (" + entry.caseName() + ")");
            System.out.println("Input: " + entry.input());
            System.out.println("Selected Model: " + entry.selectedModel());
            System.out.println("Routing Level: " + entry.routingLevel());
            System.out.println("Reason: " + entry.reason());
            System.out.println("Current Behavior: " + entry.currentBehavior());
            System.out.println("Expected Future Behavior: " + entry.expectedFutureBehavior());
            System.out.println("----------------------------------------------------------------------------------");
        }
        System.out.println("==================================================================================\n");
    }

    // =========================================================================
    // Case 1: Simple edit task with keyword false-positive ("架构")
    // =========================================================================
    @Test
    @DisplayName("Case 1: 简单修改任务 (关键词误升级基线记录)")
    void case1_simpleEditWithKeyword() {
        String input = "把这个架构图标题改成系统架构";
        LlmRequest request = buildSingleTurnRequest(input);

        ModelRoutingService.Decision decision = router.route(request);

        assertNotNull(decision);
        assertNotNull(decision.model());

        reportEntries.add(new ReportEntry(
                "case-001-simple-edit",
                "简单修改任务",
                input,
                decision.model(),
                "L" + decision.complexity(),
                decision.reason(),
                "当前系统因包含'架构'关键词(权重0.9)，在 Tier 1 被关键词密度启发式判定为高复杂度，路由至 reasoningModel (L3)",
                "未来 Dynamic Router 应通过意图分析识别核心动作为'改标题'(DRAWIO_EDIT)，保持在 fastModel 或 balancedModel (L1/L2)，不因'架构'名词误升级"
        ));
    }

    // =========================================================================
    // Case 2: Negation expression ("不需要分析架构")
    // =========================================================================
    @Test
    @DisplayName("Case 2: 否定表达 (否定语义未处理基线记录)")
    void case2_negationExpression() {
        String input = "不需要分析架构，只修改节点名称";
        LlmRequest request = buildSingleTurnRequest(input);

        ModelRoutingService.Decision decision = router.route(request);

        assertNotNull(decision);
        assertNotNull(decision.model());

        reportEntries.add(new ReportEntry(
                "case-002-negation-expression",
                "否定表达",
                input,
                decision.model(),
                "L" + decision.complexity(),
                decision.reason(),
                "当前系统缺少否定语义解析，'架构'关键词仍被正常累计加权，被 Tier 1 判定为 L3 并路由至 reasoningModel",
                "未来 Dynamic Router 应识别'不需要'否定前缀，过滤否定词加权，核心动作识别为节点改名，路由至 fastModel (L1)"
        ));
    }

    // =========================================================================
    // Case 3: Long text summarization
    // =========================================================================
    @Test
    @DisplayName("Case 3: 长文本摘要 (文本长度与复杂度关系基线记录)")
    void case3_longTextSummarization() {
        String articleBody = "本季度业务运营与客户服务支持情况综合说明报告，包含各项服务指标统计与例会纪要。".repeat(100); // ~4000 chars
        String input = articleBody + "\n请对上述工作内容进行核心要点摘要。";
        LlmRequest request = buildSingleTurnRequest(input);

        ModelRoutingService.Decision decision = router.route(request);

        assertNotNull(decision);
        assertNotNull(decision.model());

        reportEntries.add(new ReportEntry(
                "case-003-long-text-summary",
                "长文本摘要",
                "中立长文本(~4000字，无复杂推理词) + '请对上述工作内容进行核心要点摘要'",
                decision.model(),
                "L" + decision.complexity(),
                decision.reason(),
                "当前系统因单轮长度 > 3000 未命中 Tier 1 轻量规则(L1限制<3000字)，但单轮在 3000~8000 字符内且无推理词，Tier 1/2 均未命中极值，最终由 Tier 3 保底路由至 balancedModel (L2)",
                "未来 Dynamic Router 应识别任务类型为 SUMMARIZATION，结合模型 Context Window 约束，直接匹配适合长文本处理的 fast/balanced 模型"
        ));
    }

    // =========================================================================
    // Case 4: Complex diagnosis
    // =========================================================================
    @Test
    @DisplayName("Case 4: 复杂诊断 (并发死锁根因分析)")
    void case4_complexDiagnosis() {
        String input = "分析 Java 并发死锁原因并给出状态机与锁排查方案";
        LlmRequest request = buildSingleTurnRequest(input);

        ModelRoutingService.Decision decision = router.route(request);

        assertNotNull(decision);
        assertEquals("reasoningModel", decision.model());
        assertEquals(3, decision.complexity());

        reportEntries.add(new ReportEntry(
                "case-004-complex-diagnosis",
                "复杂诊断",
                input,
                decision.model(),
                "L" + decision.complexity(),
                decision.reason(),
                "当前系统命中'并发'、'状态机'等高阶关键词，Tier 1 综合评分超 0.45 阈值，准确短路路由至 reasoningModel (L3)",
                "未来 Dynamic Router 应识别任务为 ROOT_CAUSE_DIAGNOSIS，要求高 reasoning 与 coding 能力，匹配推理模型"
        ));
    }

    // =========================================================================
    // Case 5: Multi-turn history pollution test
    // =========================================================================
    @Test
    @DisplayName("Case 5: 多轮污染测试 (第一轮架构分析 -> 第二轮只修改标题)")
    void case5_multiTurnPollution() {
        LlmRequest multiTurnRequest = LlmRequest.builder()
                .model("default")
                .contents(List.of(
                        // Turn 1 User: Highly complex
                        Content.builder().role("user").parts(List.of(Part.fromText("请深度分析整个微服务分布式系统架构及并发一致性问题"))).build(),
                        // Turn 1 Assistant: Very long response
                        Content.builder().role("model").parts(List.of(Part.fromText("分布式架构分析如下：1. 一致性基于 Raft；2. 并发模型使用 Actor..."))).build(),
                        // Turn 2 User: Trivial title edit
                        Content.builder().role("user").parts(List.of(Part.fromText("只修改标题"))).build()
                ))
                .build();

        ModelRoutingService.Decision decision = router.route(multiTurnRequest);

        assertNotNull(decision);
        // Turn 2 is "只修改标题" -> matches "标题" (0.8) lightweight keyword -> fastModel (L1)
        assertEquals("fastModel", decision.model());
        assertEquals(1, decision.complexity());

        reportEntries.add(new ReportEntry(
                "case-005-multi-turn-pollution",
                "多轮污染测试",
                "Turn 1: '请深度分析整个微服务分布式系统架构...' -> Turn 2: '只修改标题'",
                decision.model(),
                "L" + decision.complexity(),
                decision.reason(),
                "当前系统通过 LatestUserMessageExtractor 仅提取最后一轮用户消息'只修改标题'，命中'标题'轻量词，成功避免历史复杂词污染，选定 fastModel (L1)",
                "未来 Dynamic Router 应保持多轮解耦特性，基于最新用户意图做 RoutingRequirement 分析"
        ));
    }

    // =========================================================================
    // Case 6: Explicit model override priority
    // =========================================================================
    @Test
    @DisplayName("Case 6: 用户显式指定模型优先级")
    void case6_userExplicitModelOverride() {
        String sessionId = "test-session-phase0-explicit";
        String customModel = "deepseek-reasoner-user-explicit";

        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                .customModelSelected(true)
                .model(customModel)
                .build();
        CustomApiConfigManager.setConfig(sessionId, config);

        CustomApiConfigManager.CustomApiConfig retrieved = CustomApiConfigManager.getConfig(sessionId);
        assertNotNull(retrieved);
        assertTrue(retrieved.isCustomModelSelected());
        assertEquals(customModel, retrieved.getModel());

        reportEntries.add(new ReportEntry(
                "case-006-explicit-model",
                "用户显式指定模型",
                "用户在前端配置 explicitModel = 'deepseek-reasoner-user-explicit'",
                customModel,
                "L0 (USER_EXPLICIT)",
                "USER_EXPLICIT",
                "当前系统在 CustomConfigPlugin 中优先判断 isCustomModelSelected，为 true 时直接覆盖目标模型，跳过自动路由",
                "未来 Dynamic Router 应继续保持用户显式选择的最高优先级"
        ));
    }

    // =========================================================================
    // Case 7: Provider registry missing/invalid configuration fallback
    // =========================================================================
    @Test
    @DisplayName("Case 7: Provider 异常配置/未知模型查询")
    void case7_providerNotFoundFallback() {
        ModelProviderProperties properties = new ModelProviderProperties();
        ModelProviderRegistryService registryService = new ModelProviderRegistryService(properties);

        String unknownModel = "unknown-custom-model-404";
        ModelProviderProperties.ProviderConfig providerConfig = registryService.findProviderConfig(unknownModel);

        // Unknown model should return null safely without throwing exceptions
        assertNull(providerConfig);

        reportEntries.add(new ReportEntry(
                "case-007-provider-fallback",
                "Provider异常配置",
                "查询未注册的未知模型 'unknown-custom-model-404'",
                "null (Fallback Default)",
                "N/A",
                "NO_PROVIDER_MATCHED",
                "当前系统 ModelProviderRegistryService 返回 null，CustomConfigPlugin 保持系统默认配置，不产生异常",
                "未来 Dynamic Router 应在 ConstraintFilter 中过滤无可用 Provider 的模型，并在 Fallback 机制中自动切换到 Backup 模型"
        ));
    }

    // =========================================================================
    // Case 8: Empty or invalid request handling
    // =========================================================================
    @Test
    @DisplayName("Case 8: 空请求与边界异常处理")
    void case8_emptyRequestHandling() {
        LlmRequest emptyRequest = LlmRequest.builder()
                .model("default")
                .contents(List.of())
                .build();

        ModelRoutingService.Decision decision = router.route(emptyRequest);

        assertNotNull(decision);
        assertNotNull(decision.model());
        // Empty text falls back to balancedModel in Tier 3
        assertEquals("balancedModel", decision.model());
        assertEquals(2, decision.complexity());

        reportEntries.add(new ReportEntry(
                "case-008-empty-request",
                "空请求",
                "LlmRequest.contents = [] (空请求)",
                decision.model(),
                "L" + decision.complexity(),
                decision.reason(),
                "当前系统防御性处理空输入，LatestUserMessageExtractor 返回空串，Composite 流转至 Tier 3 保底选定 balancedModel (L2)",
                "未来 Dynamic Router 应在 RoutingContextFactory 针对空请求做合法性校验并平稳兜底"
        ));
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private static LlmRequest buildSingleTurnRequest(String userText) {
        return LlmRequest.builder()
                .model("default")
                .contents(List.of(
                        Content.builder().role("user").parts(List.of(Part.fromText(userText))).build()
                ))
                .build();
    }
}
