package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.CandidateModelSelector;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.DefaultModelCapabilityFilter;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.decision.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.DefaultModelRankingEngine;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.ModelRankingProperties;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.ModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.CurrentTurnVisionDetector;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RuleBasedRoutingRequirementAnalyzer;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskTypeDetector;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.InMemoryModelRuntimeProfileStore;
import cn.bugstack.ai.domain.agent.service.llm.strategy.CompositeModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.LlmClassifierModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.RuleBasedModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.SemanticVectorModelRouter;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 — Dynamic Routing Decision & Controlled Takeover Tests (Case 1 to Case 8)
 */
@DisplayName("Phase 6 Dynamic Routing Decision & Controlled Takeover Tests")
public class RoutingDecisionTest {

    private ModelProfile modelFast;
    private ModelProfile modelPlus;
    private ModelProfile modelMax;

    private ModelCatalogService catalogService;
    private CandidateModelSelector selector;
    private ModelRankingService rankingService;
    private RoutingRequirementService reqService;
    private RoutingContextFactory contextFactory;

    @BeforeEach
    void setUp() {
        modelFast = createModel("qwen-fast", "qwen3.7-flash", 50, 80, 80, 0.2, 0.5);
        modelPlus = createModel("qwen-plus", "qwen3.7-plus", 80, 90, 85, 1.0, 2.0);
        modelMax = createModel("qwen-max", "qwen3.7-max", 95, 95, 95, 10.0, 20.0);

        catalogService = new ModelCatalogService(new ModelCatalogProperties());
        catalogService.registerModel(modelFast);
        catalogService.registerModel(modelPlus);
        catalogService.registerModel(modelMax);

        selector = new CandidateModelSelector(catalogService, new DefaultModelCapabilityFilter());
        DefaultModelRankingEngine rankingEngine = new DefaultModelRankingEngine(new ModelRankingProperties());
        rankingService = new ModelRankingService(selector, rankingEngine, new InMemoryModelRuntimeProfileStore());

        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        contextFactory = new RoutingContextFactory(extractor, tokenEstimator);
        reqService = new RoutingRequirementService(
                new RuleBasedRoutingRequirementAnalyzer(new TaskTypeDetector(), new CurrentTurnVisionDetector(extractor), List.of())
        );
    }

    // =========================================================================
    // Case 1: LEGACY Mode
    // =========================================================================
    @Test
    @DisplayName("Case 1: LEGACY 模式 - 验证强制使用 Legacy Router 决策，不发生动态接管")
    void case1_legacyMode() {
        RoutingDecisionProperties props = new RoutingDecisionProperties();
        props.setMode(RoutingMode.LEGACY);

        RoutingDecisionService service = new RoutingDecisionService(rankingService, reqService, new DefaultRoutingPolicy(props), props);
        RoutingContext ctx = contextFactory.create(buildRequest("请帮我修改这个图的标题"), "editor");

        RoutingDecision decision = service.decide(ctx, "qwen3.7-plus", "LEGACY_BALANCED");

        assertEquals("qwen3.7-plus", decision.selectedModel());
        assertEquals(DecisionSource.LEGACY, decision.source());
        assertFalse(decision.isDynamicTakenOver());
    }

    // =========================================================================
    // Case 2: SHADOW Mode
    // =========================================================================
    @Test
    @DisplayName("Case 2: SHADOW 模式 - 验证实际调用 Legacy 模型，Dynamic 仅作为影子观测并记录")
    void case2_shadowMode() {
        RoutingDecisionProperties props = new RoutingDecisionProperties();
        props.setMode(RoutingMode.SHADOW);

        RoutingDecisionService service = new RoutingDecisionService(rankingService, reqService, new DefaultRoutingPolicy(props), props);
        RoutingContext ctx = contextFactory.create(buildRequest("简单翻译以下段落"), "agent");

        RoutingDecision decision = service.decide(ctx, "qwen3.7-flash", "LEGACY_FAST");

        assertEquals("qwen3.7-flash", decision.selectedModel());
        assertEquals(DecisionSource.DYNAMIC_SHADOW, decision.source());
        assertNotNull(decision.dynamicTop1Model());
        assertFalse(decision.isDynamicTakenOver());
    }

    // =========================================================================
    // Case 3: CANARY 0% Mode
    // =========================================================================
    @Test
    @DisplayName("Case 3: CANARY 0% 灰度 - 验证放量为 0 时全部回退 Legacy")
    void case3_canaryZeroPercent() {
        RoutingDecisionProperties props = new RoutingDecisionProperties();
        props.setMode(RoutingMode.CANARY);
        props.setCanaryPercentage(0);

        RoutingDecisionService service = new RoutingDecisionService(rankingService, reqService, new DefaultRoutingPolicy(props), props);
        RoutingContext ctx = contextFactory.create(buildRequest("生成微服务架构图"), "architect");

        RoutingDecision decision = service.decide(ctx, "qwen3.7-plus", "LEGACY_BALANCED");

        assertEquals("qwen3.7-plus", decision.selectedModel());
        assertEquals(DecisionSource.DYNAMIC_SHADOW, decision.source());
        assertFalse(decision.isDynamicTakenOver());
    }

    // =========================================================================
    // Case 4: CANARY 100% Mode
    // =========================================================================
    @Test
    @DisplayName("Case 4: CANARY 100% 灰度 - 验证放量为 100 时允许动态 Top1 接管")
    void case4_canaryHundredPercent() {
        RoutingDecisionProperties props = new RoutingDecisionProperties();
        props.setMode(RoutingMode.CANARY);
        props.setCanaryPercentage(100);

        RoutingDecisionService service = new RoutingDecisionService(rankingService, reqService, new DefaultRoutingPolicy(props), props);
        RoutingContext ctx = contextFactory.create(buildRequest("把标题改成用户登录流程"), "editor");

        RoutingDecision decision = service.decide(ctx, "deepseek-v4-pro", "LEGACY_REASONING");

        assertNotNull(decision.selectedModel());
        assertEquals(DecisionSource.DYNAMIC_CANARY, decision.source());
        assertTrue(decision.isDynamicTakenOver());
    }

    // =========================================================================
    // Case 5: Explicit Model Override Takes Precedence
    // =========================================================================
    @Test
    @DisplayName("Case 5: 用户显式指定模型覆盖 - 验证 Dynamic 绝不覆盖用户指定模型")
    void case5_explicitModelOverrideTakesPrecedence() {
        RoutingDecisionProperties props = new RoutingDecisionProperties();
        props.setMode(RoutingMode.DYNAMIC); // Even in full dynamic mode

        RoutingDecisionService service = new RoutingDecisionService(rankingService, reqService, new DefaultRoutingPolicy(props), props);
        LlmRequest req = LlmRequest.builder()
                .model("custom-explicit-model")
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("测试消息"))).build()))
                .build();
        RoutingContext ctx = contextFactory.create(req, "agent", "stage1", false, "custom-explicit-model");

        RoutingDecision decision = service.decide(ctx, "qwen3.7-plus", "LEGACY");

        assertEquals("custom-explicit-model", decision.selectedModel());
        assertEquals(DecisionSource.EXPLICIT_OVERRIDE, decision.source());
        assertFalse(decision.isDynamicTakenOver());
    }

    // =========================================================================
    // Case 6: Dynamic Top1 == Legacy Selected
    // =========================================================================
    @Test
    @DisplayName("Case 6: Dynamic Top1 与 Legacy 一致 - 验证策略在 DYNAMIC 模式下平滑通过")
    void case6_dynamicTop1MatchesLegacy() {
        RoutingDecisionProperties props = new RoutingDecisionProperties();
        props.setMode(RoutingMode.DYNAMIC);

        RoutingDecisionService service = new RoutingDecisionService(rankingService, reqService, new DefaultRoutingPolicy(props), props);
        RoutingContext ctx = contextFactory.create(buildRequest("把标题改成用户登录流程"), "editor");

        RoutingDecision decision = service.decide(ctx, "qwen3.7-flash", "LEGACY");

        assertTrue(decision.isDynamicTakenOver());
        assertEquals(DecisionSource.DYNAMIC_FORCED, decision.source());
    }

    // =========================================================================
    // Case 7: Dynamic Top1 != Legacy in SHADOW Mode
    // =========================================================================
    @Test
    @DisplayName("Case 7: Dynamic Top1 != Legacy 在 SHADOW 模式 - 验证差异存在时依然执行 Legacy")
    void case7_dynamicDifferentFromLegacyInShadow() {
        RoutingDecisionProperties props = new RoutingDecisionProperties();
        props.setMode(RoutingMode.SHADOW);

        RoutingDecisionService service = new RoutingDecisionService(rankingService, reqService, new DefaultRoutingPolicy(props), props);
        RoutingContext ctx = contextFactory.create(buildRequest("把标题改成用户登录流程"), "editor");

        RoutingDecision decision = service.decide(ctx, "deepseek-v4-pro", "LEGACY_MISCLASSIFIED");

        assertEquals("deepseek-v4-pro", decision.selectedModel());
        assertEquals(DecisionSource.DYNAMIC_SHADOW, decision.source());
        assertFalse(decision.isDynamicTakenOver());
    }

    // =========================================================================
    // Case 8: Deterministic Canary Hash
    // =========================================================================
    @Test
    @DisplayName("Case 8: 确定性 Canary 路由 - 验证相同 requestId 多次调用路由结果严格一致")
    void case8_deterministicCanaryHash() {
        RoutingDecisionProperties props = new RoutingDecisionProperties();
        props.setMode(RoutingMode.CANARY);
        props.setCanaryPercentage(50);

        RoutingDecisionService service = new RoutingDecisionService(rankingService, reqService, new DefaultRoutingPolicy(props), props);

        LlmRequest req = buildRequest("测试重复确定性路由请求");
        RoutingContext ctx1 = contextFactory.create(req, "analyst");
        RoutingContext ctx2 = contextFactory.create(req, "analyst");

        RoutingDecision d1 = service.decide(ctx1, "qwen3.7-plus", "LEGACY");
        RoutingDecision d2 = service.decide(ctx2, "qwen3.7-plus", "LEGACY");

        assertEquals(d1.selectedModel(), d2.selectedModel());
        assertEquals(d1.source(), d2.source());
        assertEquals(d1.isDynamicTakenOver(), d2.isDynamicTakenOver());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ModelProfile createModel(String id, String name, int reasoning, int instruction, int coding, double inPrice, double outPrice) {
        return new ModelProfile(
                id, "generic", name, true,
                new ModelCapabilities(reasoning, instruction, coding, 80, 80, 0, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.UNSUPPORTED),
                new ModelLimits(65536L, 4096L),
                new ModelPricing(BigDecimal.valueOf(inPrice), BigDecimal.valueOf(outPrice), "USD")
        );
    }

    private static LlmRequest buildRequest(String text) {
        return LlmRequest.builder()
                .model("default")
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText(text))).build()))
                .build();
    }
}
