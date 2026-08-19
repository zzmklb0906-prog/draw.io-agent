package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.CandidateModelSelector;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.DefaultModelCapabilityFilter;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.DefaultModelRankingEngine;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.ModelRankingEngine;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.ModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.RankedModel;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
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
 * Phase 4 — Dynamic Model Ranking Tests
 */
@DisplayName("Phase 4 Dynamic Model Ranking Tests")
public class ModelRankingTest {

    private final ModelRankingEngine engine = new DefaultModelRankingEngine();

    private ModelProfile modelToolAdvanced;  // tool=true, reasoning=95, context=131072
    private ModelProfile modelToolBasic;     // tool=false, reasoning=60, context=8192
    private ModelProfile modelLargeContext;  // tool=true, reasoning=85, context=65536
    private ModelProfile modelSmallContext;  // tool=true, reasoning=85, context=8192

    @BeforeEach
    void setUp() {
        modelToolAdvanced = createModel("model-adv", "Model Advanced", true, 95, 131072);
        modelToolBasic = createModel("model-basic", "Model Basic", false, 60, 8192);
        modelLargeContext = createModel("model-64k", "Model 64k", true, 85, 65536);
        modelSmallContext = createModel("model-8k", "Model 8k", true, 85, 8192);
    }

    // =========================================================================
    // Case 1: Capability Difference Ranking
    // =========================================================================
    @Test
    @DisplayName("Case 1: 能力不同模型排序 - 验证 Tool Calling 需求下支持模型的排名优势")
    void case1_capabilityDifferenceRanking() {
        RoutingRequirement req = buildRequirement(true, false, false, 4096L, 3);
        List<RankedModel> ranked = engine.rank(req, List.of(modelToolBasic, modelToolAdvanced));

        assertEquals(2, ranked.size());
        assertEquals("model-adv", ranked.get(0).modelProfile().id());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    // =========================================================================
    // Case 2: Context Window Match Ranking
    // =========================================================================
    @Test
    @DisplayName("Case 2: Context Window 容量匹配度 - 验证 32000 tokens 需求下 64k 模型排名高于 8k 模型")
    void case2_contextWindowRanking() {
        RoutingRequirement req = buildRequirement(false, false, false, 32000L, 2);
        List<RankedModel> ranked = engine.rank(req, List.of(modelSmallContext, modelLargeContext));

        assertEquals(2, ranked.size());
        assertEquals("model-64k", ranked.get(0).modelProfile().id());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
        assertTrue(ranked.get(0).breakdown().contextFit() > ranked.get(1).breakdown().contextFit());
    }

    // =========================================================================
    // Case 3: Stable Deterministic Ordering
    // =========================================================================
    @Test
    @DisplayName("Case 3: 同等能力稳定排序 - 验证评分相同时依据模型 ID 确定性排序")
    void case3_stableDeterministicOrdering() {
        ModelProfile model1 = createModel("model-alpha", "Model Alpha", true, 80, 32768);
        ModelProfile model2 = createModel("model-beta", "Model Beta", true, 80, 32768);

        RoutingRequirement req = buildRequirement(false, false, false, 4096L, 2);
        List<RankedModel> ranked = engine.rank(req, List.of(model2, model1));

        assertEquals(2, ranked.size());
        assertEquals(ranked.get(0).score(), ranked.get(1).score(), 0.001);
        assertEquals("model-alpha", ranked.get(0).modelProfile().id());
        assertEquals("model-beta", ranked.get(1).modelProfile().id());
    }

    // =========================================================================
    // Case 4: Score Breakdown Explainability
    // =========================================================================
    @Test
    @DisplayName("Case 4: Score Breakdown 可解释性 - 验证输出包含 capabilityFit, contextFit, requirementFit")
    void case4_scoreBreakdownExplainability() {
        RoutingRequirement req = buildRequirement(true, false, true, 8192L, 3);
        List<RankedModel> ranked = engine.rank(req, List.of(modelToolAdvanced));

        assertFalse(ranked.isEmpty());
        RankedModel top = ranked.get(0);

        assertNotNull(top.breakdown());
        assertTrue(top.breakdown().capabilityFit() > 0.0);
        assertTrue(top.breakdown().contextFit() > 0.0);
        assertTrue(top.breakdown().requirementFit() > 0.0);
        assertTrue(top.reason().contains("Capability Match:"));
        assertTrue(top.reason().contains("Context Fit:"));
        assertTrue(top.reason().contains("Requirement Match:"));
    }

    // =========================================================================
    // Case 5: Legacy Router Consistency & Shadow Ranking
    // =========================================================================
    @Test
    @DisplayName("Case 5: Legacy Router 决策一致性 - 验证 Shadow Ranking 运行不改变最终模型决策")
    void case5_legacyRouterConsistencyAndShadowRanking() {
        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        RoutingContextFactory contextFactory = new RoutingContextFactory(extractor, tokenEstimator);

        RuleBasedModelRouter ruleRouter = new RuleBasedModelRouter(extractor);
        SemanticVectorModelRouter semanticRouter = new SemanticVectorModelRouter(extractor);
        LlmClassifierModelRouter classifierRouter = new LlmClassifierModelRouter(extractor, semanticRouter);
        CompositeModelRouter compositeRouter = new CompositeModelRouter(extractor, semanticRouter, classifierRouter, ruleRouter);

        ModelCatalogService catalogService = new ModelCatalogService(new ModelCatalogProperties());
        catalogService.registerModel(modelToolAdvanced);
        catalogService.registerModel(modelToolBasic);

        CandidateModelSelector selector = new CandidateModelSelector(catalogService, new DefaultModelCapabilityFilter());
        ModelRankingService rankingService = new ModelRankingService(selector, engine);

        ModelRoutingService routingService = new ModelRoutingService(
                true,
                "composite",
                "qwen3.7-flash",
                "qwen3.7-plus",
                "deepseek-v4-pro",
                List.of(compositeRouter, semanticRouter, classifierRouter, ruleRouter),
                contextFactory,
                catalogService,
                selector,
                rankingService
        );

        assertNotNull(routingService.getModelRankingService());

        // Fast model task -> must return legacy fastModel
        LlmRequest fastReq = buildRequest("简单翻译以下段落为中文");
        ModelRoutingService.Decision fastDecision = routingService.route(fastReq);
        assertEquals("qwen3.7-flash", fastDecision.model());

        // Reasoning model task -> must return legacy reasoningModel
        LlmRequest reasoningReq = buildRequest("设计高并发分布式一致性状态机与调用链追踪方案");
        ModelRoutingService.Decision reasoningDecision = routingService.route(reasoningReq);
        assertEquals("deepseek-v4-pro", reasoningDecision.model());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ModelProfile createModel(String id, String name, boolean toolCalling, int reasoningScore, long contextWindow) {
        return new ModelProfile(
                id,
                "generic",
                name,
                true,
                new ModelCapabilities(reasoningScore, 80, 80, 80, toolCalling ? 90 : 0, 0, 80),
                new ModelFeatures(
                        toolCalling ? SupportStatus.SUPPORTED : SupportStatus.UNSUPPORTED,
                        SupportStatus.SUPPORTED,
                        SupportStatus.UNSUPPORTED
                ),
                new ModelLimits(contextWindow, 4096L),
                new ModelPricing(BigDecimal.valueOf(0.002), BigDecimal.valueOf(0.005), "USD")
        );
    }

    private static RoutingRequirement buildRequirement(boolean needTool, boolean needVision, boolean needStructured, long minContext, int complexity) {
        return new RoutingRequirement(
                TaskType.GENERAL_CHAT,
                complexity >= 3 ? 90 : (complexity <= 1 ? 20 : 50),
                60,
                30,
                needStructured ? 80 : 0,
                needTool ? 80 : 0,
                needVision,
                minContext,
                2048L,
                "agent_test",
                RequirementEvidence.empty()
        );
    }

    private static LlmRequest buildRequest(String text) {
        return LlmRequest.builder()
                .model("default")
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText(text))).build()))
                .build();
    }
}
