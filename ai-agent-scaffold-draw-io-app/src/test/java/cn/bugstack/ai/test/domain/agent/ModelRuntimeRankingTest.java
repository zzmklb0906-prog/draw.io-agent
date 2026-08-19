package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.CandidateModelSelector;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.DefaultModelCapabilityFilter;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.InMemoryModelRuntimeProfileStore;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelRuntimeProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.RuntimeHealth;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 — Intent-driven Runtime-aware Model Ranking Tests (Case 1 to Case 10)
 */
@DisplayName("Phase 5 Intent-driven Runtime-aware Model Ranking Tests")
public class ModelRuntimeRankingTest {

    private final ModelRankingProperties properties = new ModelRankingProperties();
    private final DefaultModelRankingEngine rankingEngine = new DefaultModelRankingEngine(properties);

    private ModelProfile modelLight;    // reasoning=40, context=32768, price=0.001
    private ModelProfile modelHeavy;    // reasoning=95, context=131072, price=0.02
    private ModelProfile modelFast;     // reasoning=70, context=65536, price=0.002
    private ModelProfile modelSlow;     // reasoning=70, context=65536, price=0.002

    @BeforeEach
    void setUp() {
        modelLight = createModel("model-light", "Model Light", 40, 32768, 0.5, 1.0);
        modelHeavy = createModel("model-heavy", "Model Heavy", 95, 131072, 20.0, 50.0);
        modelFast = createModel("model-fast", "Model Fast", 70, 65536, 1.0, 2.0);
        modelSlow = createModel("model-slow", "Model Slow", 70, 65536, 1.0, 2.0);
    }

    // =========================================================================
    // Case 1: Ranking Does NOT Re-read Raw Text
    // =========================================================================
    @Test
    @DisplayName("Case 1: 需求单一真实源 - 验证 Ranking Engine 不重新解析原始文本关键词，仅消费结构化 Requirement")
    void case1_rankingDoesNotReReadRawText() {
        // Raw text contains heavy keywords ("架构、并发、状态机")
        // But structured requirement explicitly specifies low reasoning requirement (e.g. simple edit)
        RoutingRequirement lowDemandReq = new RoutingRequirement(
                TaskType.SIMPLE_EDIT,
                20, 60, 10, 20, 10, false, 4096L, 1024L, "editor", RequirementEvidence.empty()
        );

        List<RankedModel> ranked = rankingEngine.rank(lowDemandReq, List.of(modelLight, modelHeavy));
        assertFalse(ranked.isEmpty());

        // Under saturation mechanism and low requirement, modelLight has capabilityFit=1.0 and lower cost
        RankedModel top = ranked.get(0);
        assertEquals(1.0, top.breakdown().capabilityFit());
        // modelHeavy doesn't get infinite extra capability bonus
        assertTrue(top.score() >= 0.70);
    }

    // =========================================================================
    // Case 2: RoutingRequirementAnalyzer Integration
    // =========================================================================
    @Test
    @DisplayName("Case 2: 需求分析器 - 验证从 Context 推导 TaskType 和能力需求，Analyzer 绝不选择模型")
    void case2_requirementAnalyzer() {
        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        RoutingContextFactory contextFactory = new RoutingContextFactory(extractor, tokenEstimator);

        RuleBasedRoutingRequirementAnalyzer analyzer = new RuleBasedRoutingRequirementAnalyzer(
                new TaskTypeDetector(),
                new CurrentTurnVisionDetector(extractor),
                List.of()
        );

        LlmRequest req = buildRequest("把标题改成用户登录流程");
        RoutingContext ctx = contextFactory.create(req, "editor");

        RoutingRequirement analyzed = analyzer.analyze(ctx);
        assertNotNull(analyzed);
        assertEquals(TaskType.SIMPLE_EDIT, analyzed.taskType());
        assertTrue(analyzed.reasoningRequired() <= 40);
    }

    // =========================================================================
    // Case 3: Reliability Scoring
    // =========================================================================
    @Test
    @DisplayName("Case 3: 可靠性评分 - 验证高成功率模型获得更高 reliabilityFit")
    void case3_reliabilityScoring() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT, 60, 60, 30, 30, 20, false, 4096L, 2048L, "agent", RequirementEvidence.empty()
        );

        ModelRuntimeProfile goodRuntime = new ModelRuntimeProfile("model-fast", 0.99, 0.01, 800.0, null, RuntimeHealth.HEALTHY, 500L);
        ModelRuntimeProfile flakyRuntime = new ModelRuntimeProfile("model-slow", 0.60, 0.20, 800.0, null, RuntimeHealth.HEALTHY, 500L);

        List<RankedModel> ranked = rankingEngine.rank(
                req,
                List.of(modelFast, modelSlow),
                Map.of("model-fast", goodRuntime, "model-slow", flakyRuntime)
        );

        assertEquals("model-fast", ranked.get(0).modelProfile().id());
        assertTrue(ranked.get(0).breakdown().reliabilityFit() > ranked.get(1).breakdown().reliabilityFit());
    }

    // =========================================================================
    // Case 4: Runtime UNKNOWN Neutral Protection
    // =========================================================================
    @Test
    @DisplayName("Case 4: Runtime UNKNOWN 中立保护 - 验证无运行历史的新模型使用中立分 0.5，不被淘汰")
    void case4_unknownRuntimeNeutralProtection() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT, 50, 50, 20, 20, 20, false, 4096L, 2048L, "agent", RequirementEvidence.empty()
        );

        // modelFast has unknown runtime (no history)
        List<RankedModel> ranked = rankingEngine.rank(req, List.of(modelFast), Map.of());

        assertEquals(1, ranked.size());
        assertEquals(0.5, ranked.get(0).breakdown().reliabilityFit());
        assertEquals(0.5, ranked.get(0).breakdown().latencyFit());
        assertFalse(ranked.get(0).runtimeEvidenceAvailable());
    }

    // =========================================================================
    // Case 5: UNAVAILABLE Exclusion
    // =========================================================================
    @Test
    @DisplayName("Case 5: UNAVAILABLE 排除机制 - 验证健康状态为不可用的模型从推荐候选中剔除")
    void case5_unavailableModelExclusion() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT, 50, 50, 20, 20, 20, false, 4096L, 2048L, "agent", RequirementEvidence.empty()
        );

        ModelRuntimeProfile deadRuntime = new ModelRuntimeProfile("model-fast", 0.0, 1.0, 9999.0, null, RuntimeHealth.UNAVAILABLE, 100L);
        ModelRuntimeProfile aliveRuntime = new ModelRuntimeProfile("model-slow", 0.95, 0.02, 1200.0, null, RuntimeHealth.HEALTHY, 100L);

        List<RankedModel> ranked = rankingEngine.rank(
                req,
                List.of(modelFast, modelSlow),
                Map.of("model-fast", deadRuntime, "model-slow", aliveRuntime)
        );

        assertEquals(1, ranked.size());
        assertEquals("model-slow", ranked.get(0).modelProfile().id());
    }

    // =========================================================================
    // Case 6: Latency Fit
    // =========================================================================
    @Test
    @DisplayName("Case 6: 响应延迟评分 - 验证低延迟模型获得更高 latencyFit")
    void case6_latencyFit() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT, 70, 70, 30, 30, 20, false, 4096L, 2048L, "agent", RequirementEvidence.empty()
        );

        ModelRuntimeProfile fastRuntime = new ModelRuntimeProfile("model-fast", 0.98, 0.0, 500.0, null, RuntimeHealth.HEALTHY, 200L);
        ModelRuntimeProfile slowRuntime = new ModelRuntimeProfile("model-slow", 0.98, 0.0, 7000.0, null, RuntimeHealth.HEALTHY, 200L);

        List<RankedModel> ranked = rankingEngine.rank(
                req,
                List.of(modelFast, modelSlow),
                Map.of("model-fast", fastRuntime, "model-slow", slowRuntime)
        );

        assertTrue(ranked.get(0).breakdown().latencyFit() > ranked.get(1).breakdown().latencyFit());
    }

    // =========================================================================
    // Case 7: Cost Fit & Cost Estimation
    // =========================================================================
    @Test
    @DisplayName("Case 7: 成本估算与 Cost Fit - 验证按请求估算成本，低价模型获得更高 costFit")
    void case7_costFitAndEstimation() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT, 40, 40, 20, 20, 20, false, 8000L, 2000L, "agent", RequirementEvidence.empty()
        );

        List<RankedModel> ranked = rankingEngine.rank(req, List.of(modelLight, modelHeavy));

        RankedModel lightRanked = ranked.stream().filter(r -> r.modelProfile().id().equals("model-light")).findFirst().orElseThrow();
        RankedModel heavyRanked = ranked.stream().filter(r -> r.modelProfile().id().equals("model-heavy")).findFirst().orElseThrow();

        assertNotNull(lightRanked.estimatedCost());
        assertNotNull(heavyRanked.estimatedCost());
        assertTrue(lightRanked.estimatedCost() < heavyRanked.estimatedCost());
        assertTrue(lightRanked.breakdown().costFit() > heavyRanked.breakdown().costFit());
    }

    // =========================================================================
    // Case 8: Capability Oversupply Saturation (Anti-Oversupply Bias)
    // =========================================================================
    @Test
    @DisplayName("Case 8: 能力饱和机制 - 验证需求为 30 时，超大模型(reasoning=95)不会获得无界超额优势")
    void case8_capabilityOversupplySaturation() {
        RoutingRequirement lowReq = new RoutingRequirement(
                TaskType.SIMPLE_EDIT, 30, 40, 0, 0, 0, false, 4096L, 1024L, "agent", RequirementEvidence.empty()
        );

        List<RankedModel> ranked = rankingEngine.rank(lowReq, List.of(modelLight, modelHeavy));

        RankedModel lightRanked = ranked.stream().filter(r -> r.modelProfile().id().equals("model-light")).findFirst().orElseThrow();
        RankedModel heavyRanked = ranked.stream().filter(r -> r.modelProfile().id().equals("model-heavy")).findFirst().orElseThrow();

        // Both meet reasoning requirement (40 >= 30, 95 >= 30) -> both get saturated capabilityFit = 1.0
        assertEquals(1.0, lightRanked.breakdown().capabilityFit(), 0.01);
        assertEquals(1.0, heavyRanked.breakdown().capabilityFit(), 0.01);
    }

    // =========================================================================
    // Case 9: Stable Tie Break
    // =========================================================================
    @Test
    @DisplayName("Case 9: 确定性稳定排序 - 验证同等分数下按稳定 Tie-Break 规则输出")
    void case9_stableTieBreak() {
        ModelProfile model1 = createModel("model-a", "Model A", 70, 32768, 0.002, 0.005);
        ModelProfile model2 = createModel("model-b", "Model B", 70, 32768, 0.002, 0.005);

        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT, 70, 70, 0, 0, 0, false, 4096L, 1024L, "agent", RequirementEvidence.empty()
        );

        List<RankedModel> ranked = rankingEngine.rank(req, List.of(model2, model1));

        assertEquals(2, ranked.size());
        assertEquals(ranked.get(0).score(), ranked.get(1).score(), 0.001);
        assertEquals("model-a", ranked.get(0).modelProfile().id());
        assertEquals("model-b", ranked.get(1).modelProfile().id());
    }

    // =========================================================================
    // Case 10: Legacy Router Consistency & Store Integration
    // =========================================================================
    @Test
    @DisplayName("Case 10: Legacy Router 决策一致性 - 验证 Shadow Mode 下 Legacy 选择结果 100% 不变")
    void case10_legacyRouterConsistencyAndStoreIntegration() {
        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        RoutingContextFactory contextFactory = new RoutingContextFactory(extractor, tokenEstimator);

        RuleBasedModelRouter ruleRouter = new RuleBasedModelRouter(extractor);
        SemanticVectorModelRouter semanticRouter = new SemanticVectorModelRouter(extractor);
        LlmClassifierModelRouter classifierRouter = new LlmClassifierModelRouter(extractor, semanticRouter);
        CompositeModelRouter compositeRouter = new CompositeModelRouter(extractor, semanticRouter, classifierRouter, ruleRouter);

        ModelCatalogService catalogService = new ModelCatalogService(new ModelCatalogProperties());
        catalogService.registerModel(modelLight);
        catalogService.registerModel(modelHeavy);

        InMemoryModelRuntimeProfileStore store = new InMemoryModelRuntimeProfileStore();
        store.save(new ModelRuntimeProfile("model-light", 0.99, 0.0, 500.0, 0.0001, RuntimeHealth.HEALTHY, 100L));

        CandidateModelSelector selector = new CandidateModelSelector(catalogService, new DefaultModelCapabilityFilter());
        ModelRankingService rankingService = new ModelRankingService(selector, rankingEngine, store);

        RoutingRequirementService reqService = new RoutingRequirementService(
                new RuleBasedRoutingRequirementAnalyzer(new TaskTypeDetector(), new CurrentTurnVisionDetector(extractor), List.of())
        );

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
                rankingService,
                reqService
        );

        // Fast request
        LlmRequest fastReq = buildRequest("简单翻译");
        ModelRoutingService.Decision fastDecision = routingService.route(fastReq);
        assertEquals("qwen3.7-flash", fastDecision.model());

        // Reasoning request
        LlmRequest reasoningReq = buildRequest("设计高并发分布式系统架构");
        ModelRoutingService.Decision reasoningDecision = routingService.route(reasoningReq);
        assertEquals("deepseek-v4-pro", reasoningDecision.model());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ModelProfile createModel(String id, String name, int reasoning, long contextWindow, double inPrice, double outPrice) {
        return new ModelProfile(
                id,
                "generic",
                name,
                true,
                new ModelCapabilities(reasoning, 80, 80, 80, 80, 0, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.UNSUPPORTED),
                new ModelLimits(contextWindow, 4096L),
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
