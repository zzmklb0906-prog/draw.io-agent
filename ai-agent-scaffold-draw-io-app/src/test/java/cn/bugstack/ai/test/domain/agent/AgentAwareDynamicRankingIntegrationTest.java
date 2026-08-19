package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.*;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for Agent-aware Differential Ranking.
 *
 * <p>Validates that the same user prompt submitted to different agents (e.g. analyst vs drawer)
 * produces different {@link RoutingRequirement}s and naturally drives different {@link RankingResult}s,
 * without any hard-coded agent model preference policy.</p>
 */
class AgentAwareDynamicRankingIntegrationTest {

    private RoutingContextFactory contextFactory;
    private RoutingRequirementService requirementService;
    private ModelConstraintFilteringService filteringService;
    private DynamicModelRankingService rankingService;

    @BeforeEach
    void setUp() {
        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        this.contextFactory = new RoutingContextFactory(extractor, tokenEstimator);

        TaskTypeDetector detector = new TaskTypeDetector();
        CurrentTurnVisionDetector visionDetector = new CurrentTurnVisionDetector(extractor);
        List<AgentRequirementPolicy> policies = List.of(
                new AnalystAgentRequirementPolicy(),
                new DrawerAgentRequirementPolicy()
        );
        RuleBasedRoutingRequirementAnalyzer analyzer = new RuleBasedRoutingRequirementAnalyzer(detector, visionDetector, policies);
        this.requirementService = new RoutingRequirementService(analyzer);

        DefaultModelConstraintFilter constraintFilter = new DefaultModelConstraintFilter();
        ModelCatalogProperties catalogProps = new ModelCatalogProperties();
        ModelCatalogService catalogService = new ModelCatalogService(catalogProps);
        this.filteringService = new ModelConstraintFilteringService(catalogService, constraintFilter);

        ModelScoringProperties scoringProps = new ModelScoringProperties();
        scoringProps.validate();
        WeightedModelScorer scorer = new WeightedModelScorer(scoringProps);
        DefaultModelRanker ranker = new DefaultModelRanker(scorer);
        this.rankingService = new DynamicModelRankingService(ranker);
    }

    @Test
    void samePrompt_analystVsDrawer_producesDifferentRequirementsAndRankings() {
        // Same user prompt for both agents
        LlmRequest request = LlmRequest.builder()
                .model("test")
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("分析电商订单系统的状态流转与边界异常"))).build()))
                .build();

        // 1. Pipeline execution for agent_analyst
        RoutingContext ctxAnalyst = contextFactory.create(request, "agent_analyst");
        RoutingRequirement reqAnalyst = requirementService.tryAnalyze(ctxAnalyst).orElseThrow();

        // 2. Pipeline execution for agent_drawer
        RoutingContext ctxDrawer = contextFactory.create(request, "agent_drawer");
        RoutingRequirement reqDrawer = requirementService.tryAnalyze(ctxDrawer).orElseThrow();

        // Verify Requirement differential
        assertNotEquals(reqAnalyst.structuredOutputRequired(), reqDrawer.structuredOutputRequired(),
                "Drawer requirement must have different structuredOutput demand than Analyst");
        assertTrue(reqDrawer.structuredOutputRequired() > reqAnalyst.structuredOutputRequired());
        assertTrue(reqDrawer.toolCallingRequired() > reqAnalyst.toolCallingRequired());

        // 3. Construct candidate models
        // Model A (Analyst-favored): Fully satisfies Analyst needs (reasoning 85, instruction 90, coding 50, structured 88, tool 30), highly cost-effective (1.0/4.0 CNY)
        ModelProfile modelAnalystFavored = new ModelProfile(
                "model-analyst-favored", "test", "model-analyst-favored", true,
                new ModelCapabilities(85, 90, 50, 88, 30, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(1048576L, 131072L),
                new ModelPricing(BigDecimal.valueOf(1.0), BigDecimal.valueOf(4.0), "CNY")
        );

        // Model B (Drawer-favored): Fully satisfies Drawer extreme demands (reasoning 80, instruction 95, coding 60, structured 98, tool 90), higher cost (4.0/16.0 CNY)
        ModelProfile modelDrawerFavored = new ModelProfile(
                "model-drawer-favored", "test", "model-drawer-favored", true,
                new ModelCapabilities(80, 95, 60, 98, 90, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(1048576L, 131072L),
                new ModelPricing(BigDecimal.valueOf(4.0), BigDecimal.valueOf(16.0), "CNY")
        );

        List<ModelProfile> candidates = List.of(modelAnalystFavored, modelDrawerFavored);

        // 4. Hard filter
        ModelFilterResult filterAnalyst = filteringService.filter(reqAnalyst, candidates);
        ModelFilterResult filterDrawer = filteringService.filter(reqDrawer, candidates);

        // 5. Dynamic Ranking
        RankingResult rankingAnalyst = rankingService.rank(reqAnalyst, filterAnalyst);
        RankingResult rankingDrawer = rankingService.rank(reqDrawer, filterDrawer);

        // Verify differential ranking
        String topAnalyst = rankingAnalyst.topCandidate().orElseThrow().model().id();
        String topDrawer = rankingDrawer.topCandidate().orElseThrow().model().id();

        assertEquals("model-analyst-favored", topAnalyst,
                "Analyst requirement must favor cost-effective model that fully satisfies analyst criteria");
        assertEquals("model-drawer-favored", topDrawer,
                "Drawer requirement must favor extreme structured/tool specialist model despite higher cost");
    }
}
