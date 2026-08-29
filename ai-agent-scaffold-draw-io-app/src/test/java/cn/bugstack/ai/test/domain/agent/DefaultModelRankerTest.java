package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCapabilities;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelFeatures;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelLimits;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelPricing;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelTier;
import cn.bugstack.ai.domain.agent.service.llm.catalog.SupportStatus;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.CandidateScore;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DefaultModelRanker;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScoringProperties;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RankingResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.WeightedModelScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultModelRanker} (Phase 5 Model Ranking & Determinism).
 */
class DefaultModelRankerTest {

    private DefaultModelRanker ranker;

    @BeforeEach
    void setUp() {
        WeightedModelScorer scorer = new WeightedModelScorer(new ModelScoringProperties());
        this.ranker = new DefaultModelRanker(scorer);
    }

    @Test
    void noCandidates_returnsEmptyRankingSafely() {
        RoutingRequirement req = createReq(50, 50, 50, 50, 50);

        RankingResult nullResult = ranker.rank(req, null);
        assertTrue(nullResult.isEmpty());
        assertTrue(nullResult.topCandidate().isEmpty());

        RankingResult emptyResult = ranker.rank(req, List.of());
        assertTrue(emptyResult.isEmpty());
    }

    @Test
    void singleCandidate_returnsAsTopCandidate() {
        RoutingRequirement req = createReq(50, 50, 50, 50, 50);
        ModelProfile model = createModel("only-one", 80, 80, 80, 80, 80, BigDecimal.ONE, BigDecimal.TEN);

        RankingResult result = ranker.rank(req, List.of(model));

        assertEquals(1, result.rankedCandidates().size());
        assertTrue(result.topCandidate().isPresent());
        assertEquals("only-one", result.topCandidate().get().model().id());
    }

    @Test
    void deterministicTieBreak_lexicalByIdWhenScoresAndCostAreEqual() {
        RoutingRequirement req = createReq(50, 50, 50, 50, 50);
        // Identical capabilities and pricing, different IDs
        ModelProfile modelZ = createModel("model-z", 80, 80, 80, 80, 80, BigDecimal.ONE, BigDecimal.TEN);
        ModelProfile modelA = createModel("model-a", 80, 80, 80, 80, 80, BigDecimal.ONE, BigDecimal.TEN);

        RankingResult result1 = ranker.rank(req, List.of(modelZ, modelA));
        RankingResult result2 = ranker.rank(req, List.of(modelA, modelZ));

        // Both executions must order model-a before model-z deterministically
        assertEquals("model-a", result1.rankedCandidates().get(0).model().id());
        assertEquals("model-z", result1.rankedCandidates().get(1).model().id());

        assertEquals("model-a", result2.rankedCandidates().get(0).model().id());
        assertEquals("model-z", result2.rankedCandidates().get(1).model().id());
    }

    @Test
    void missingPricing_doesNotWinCostTieBreak() {
        RoutingRequirement req = createReq(50, 50, 50, 50, 50);
        // Model with known positive price
        ModelProfile modelKnown = createModel("known-price", 80, 80, 80, 80, 80, BigDecimal.valueOf(0.003), BigDecimal.valueOf(0.006));
        // Model with missing pricing (pricing == null -> estimatedCost = -1.0)
        ModelProfile modelMissing = new ModelProfile(
                "missing-price", "qwen", "missing-price", true,
                new ModelCapabilities(80, 80, 80, 80, 80, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(1048576L, 131072L),
                null // null pricing
        );

        RankingResult result = ranker.rank(req, List.of(modelMissing, modelKnown));

        // Missing price model must NOT win tie-break over known price model due to -1 sentinel
        assertEquals("known-price", result.rankedCandidates().get(0).model().id(),
                "Known price model must rank before missing price model in cost tie-break");
    }

    @Test
    void inputOrderIndependence_yieldsIdenticalRanking() {
        RoutingRequirement req = createReq(90, 90, 80, 80, 80);
        ModelProfile max = createModel("qwen3.8-max", 92, 95, 94, 95, 92, BigDecimal.valueOf(12.0), BigDecimal.valueOf(36.0));
        ModelProfile plus = createModel("qwen3.7-plus", 75, 88, 80, 88, 88, BigDecimal.valueOf(2.0), BigDecimal.valueOf(8.0));
        ModelProfile flash = createModel("qwen3.7-flash", 55, 82, 65, 80, 80, BigDecimal.valueOf(0.2), BigDecimal.valueOf(0.8));

        RankingResult r1 = ranker.rank(req, List.of(flash, plus, max));
        RankingResult r2 = ranker.rank(req, List.of(max, flash, plus));
        RankingResult r3 = ranker.rank(req, List.of(plus, max, flash));

        List<String> order1 = r1.rankedCandidates().stream().map(cs -> cs.model().id()).toList();
        List<String> order2 = r2.rankedCandidates().stream().map(cs -> cs.model().id()).toList();
        List<String> order3 = r3.rankedCandidates().stream().map(cs -> cs.model().id()).toList();

        assertEquals(order1, order2, "Ranking must be independent of catalog input order");
        assertEquals(order1, order3, "Ranking must be independent of catalog input order");
    }

    // =========================================================================
    // Tier-First Cheapest-Sufficient Acceptance Tests
    // =========================================================================

    @Test
    void lightweightTask_cheapestSufficientFastModelWins_despiteStrongerCandidates() {
        // Lightweight SIMPLE_EDIT: demands 30 reasoning, 70 instruction
        RoutingRequirement req = new RoutingRequirement(
                TaskType.SIMPLE_EDIT,
                30, 70, 10, 30, 10,
                false, 10000L, 512L, "agent_analyst", RequirementEvidence.empty()
        );

        // 1 Fast model: sufficient and cheap
        ModelProfile fastModel = createModelWithTier("fast-cheap", ModelTier.FAST, 55, 82, 65, 80, 80, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));

        // 4 Stronger models in Balanced and Reasoning tiers with higher total scores
        ModelProfile strong1 = createModelWithTier("balanced-1", ModelTier.BALANCED, 75, 88, 80, 88, 88, BigDecimal.valueOf(2.00), BigDecimal.valueOf(8.00));
        ModelProfile strong2 = createModelWithTier("balanced-2", ModelTier.BALANCED, 80, 90, 85, 90, 90, BigDecimal.valueOf(3.00), BigDecimal.valueOf(10.00));
        ModelProfile strong3 = createModelWithTier("reasoning-1", ModelTier.REASONING, 92, 95, 94, 95, 92, BigDecimal.valueOf(12.00), BigDecimal.valueOf(36.00));
        ModelProfile strong4 = createModelWithTier("reasoning-2", ModelTier.REASONING, 95, 98, 96, 98, 95, BigDecimal.valueOf(20.00), BigDecimal.valueOf(60.00));

        RankingResult result = ranker.rank(req, List.of(strong3, strong1, fastModel, strong4, strong2));

        assertFalse(result.isEmpty());
        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("fast-cheap", top.model().id(),
                "Cheapest sufficient Fast model must win for lightweight request despite 4 stronger candidates");
        assertEquals("FAST_CHEAPEST_SUFFICIENT", result.selectionReason());
    }

    @Test
    void ambiguousRequest_doesNotSelectFast() {
        // Ambiguous GENERAL_CHAT: target tier is BALANCED
        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT,
                40, 60, 10, 20, 10,
                false, 10000L, 2048L, "agent_analyst", RequirementEvidence.empty()
        );

        ModelProfile fast = createModelWithTier("fast-model", ModelTier.FAST, 55, 82, 65, 80, 80, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        ModelProfile balanced = createModelWithTier("balanced-model", ModelTier.BALANCED, 75, 88, 80, 88, 88, BigDecimal.valueOf(2.00), BigDecimal.valueOf(8.00));

        RankingResult result = ranker.rank(req, List.of(fast, balanced));

        assertFalse(result.isEmpty());
        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("balanced-model", top.model().id(),
                "Ambiguous/default request must NOT select Fast model merely because Fast is cheaper");
        assertEquals("BALANCED_CHEAPEST_SUFFICIENT", result.selectionReason());
    }

    @Test
    void complexDiagnosis_selectsReasoningTierCandidate() {
        // Complex DIAGNOSE: target tier is REASONING
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DIAGNOSE,
                85, 80, 70, 50, 30,
                false, 10000L, 4096L, "agent_analyst", RequirementEvidence.empty()
        );

        ModelProfile fast = createModelWithTier("fast-model", ModelTier.FAST, 55, 82, 65, 80, 80, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        ModelProfile balanced = createModelWithTier("balanced-model", ModelTier.BALANCED, 75, 88, 80, 88, 88, BigDecimal.valueOf(2.00), BigDecimal.valueOf(8.00));
        ModelProfile reasoning = createModelWithTier("reasoning-model", ModelTier.REASONING, 92, 95, 94, 95, 92, BigDecimal.valueOf(12.00), BigDecimal.valueOf(36.00));

        RankingResult result = ranker.rank(req, List.of(fast, balanced, reasoning));

        assertFalse(result.isEmpty());
        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("reasoning-model", top.model().id(),
                "Complex diagnosis must select Reasoning tier model");
        assertEquals("REASONING_CHEAPEST_SUFFICIENT", result.selectionReason());
    }

    @Test
    void tierEscalation_whenNoFastModelSufficient_escalatesToBalanced() {
        // High instruction requirement for simple task (instruction = 95)
        RoutingRequirement req = new RoutingRequirement(
                TaskType.SIMPLE_EDIT,
                30, 95, 10, 30, 10,
                false, 10000L, 512L, "agent_analyst", RequirementEvidence.empty()
        );

        // Fast model has instruction = 70 (< 95 * 0.85 = 80.75 -> insufficient under 0.85 threshold)
        ModelProfile fast = createModelWithTier("fast-model", ModelTier.FAST, 55, 70, 65, 80, 80, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        // Balanced model has instruction = 96 (sufficient)
        ModelProfile balanced = createModelWithTier("balanced-model", ModelTier.BALANCED, 75, 96, 80, 88, 88, BigDecimal.valueOf(2.00), BigDecimal.valueOf(8.00));

        RankingResult result = ranker.rank(req, List.of(fast, balanced));

        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("balanced-model", top.model().id(),
                "When Fast model is insufficient, tier must escalate to Balanced");
        assertEquals("TIER_ESCALATED_TO_BALANCED", result.selectionReason());
    }

    @Test
    void summarizeTask_routesToSufficientFastModel_addingStrongerCandidatesDoesNotDisplaceIt() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.SUMMARIZE,
                30, 70, 10, 30, 10,
                false, 10000L, 1024L, "agent_analyst", RequirementEvidence.empty()
        );

        ModelProfile fast = createModelWithTier("fast-flash", ModelTier.FAST, 55, 82, 65, 80, 80, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        ModelProfile balanced = createModelWithTier("balanced-plus", ModelTier.BALANCED, 75, 88, 80, 88, 88, BigDecimal.valueOf(2.00), BigDecimal.valueOf(8.00));
        ModelProfile reasoning1 = createModelWithTier("reasoning-max", ModelTier.REASONING, 92, 95, 94, 95, 92, BigDecimal.valueOf(12.00), BigDecimal.valueOf(36.00));
        ModelProfile reasoning2 = createModelWithTier("reasoning-ultra", ModelTier.REASONING, 98, 98, 98, 98, 98, BigDecimal.valueOf(50.00), BigDecimal.valueOf(150.00));

        RankingResult result = ranker.rank(req, List.of(reasoning1, balanced, fast, reasoning2));

        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("fast-flash", top.model().id(),
                "SUMMARIZE must route to sufficient Fast model, and adding stronger candidates cannot displace it");
        assertEquals("FAST_CHEAPEST_SUFFICIENT", result.selectionReason());
    }

    @Test
    void extractTask_routesToSufficientFastModel_addingStrongerCandidatesDoesNotDisplaceIt() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.EXTRACT,
                30, 70, 10, 30, 10,
                false, 10000L, 1024L, "agent_analyst", RequirementEvidence.empty()
        );

        ModelProfile fast = createModelWithTier("fast-flash", ModelTier.FAST, 55, 82, 65, 80, 80, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        ModelProfile balanced = createModelWithTier("balanced-plus", ModelTier.BALANCED, 75, 88, 80, 88, 88, BigDecimal.valueOf(2.00), BigDecimal.valueOf(8.00));
        ModelProfile reasoning1 = createModelWithTier("reasoning-max", ModelTier.REASONING, 92, 95, 94, 95, 92, BigDecimal.valueOf(12.00), BigDecimal.valueOf(36.00));

        RankingResult result = ranker.rank(req, List.of(reasoning1, balanced, fast));

        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("fast-flash", top.model().id(),
                "EXTRACT must route to sufficient Fast model, and adding stronger candidates cannot displace it");
        assertEquals("FAST_CHEAPEST_SUFFICIENT", result.selectionReason());
    }

    @Test
    void drawioReview_targetsReasoningTier() {
        assertEquals(ModelTier.REASONING, DefaultModelRanker.resolveTargetTier(TaskType.DRAWIO_REVIEW));

        RoutingRequirement req = new RoutingRequirement(
                TaskType.DRAWIO_REVIEW,
                75, 85, 60, 85, 30,
                false, 10000L, 4096L, "agent_reviewer", RequirementEvidence.empty()
        );

        ModelProfile fast = createModelWithTier("fast-flash", ModelTier.FAST, 55, 82, 65, 80, 80, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        ModelProfile balanced = createModelWithTier("balanced-plus", ModelTier.BALANCED, 75, 88, 80, 88, 88, BigDecimal.valueOf(2.00), BigDecimal.valueOf(8.00));
        ModelProfile reasoning = createModelWithTier("reasoning-max", ModelTier.REASONING, 92, 95, 94, 95, 92, BigDecimal.valueOf(12.00), BigDecimal.valueOf(36.00));

        RankingResult result = ranker.rank(req, List.of(fast, balanced, reasoning));

        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("reasoning-max", top.model().id(),
                "DRAWIO_REVIEW must target Reasoning tier and select reasoning model");
        assertEquals("REASONING_CHEAPEST_SUFFICIENT", result.selectionReason());
    }

    @Test
    void noSufficientFallback_prefersHighestCapabilityFit_beforeCostOrTotalScalarScore() {
        // High requirements: reasoning 95, instruction 95 (no model meets 0.85 * 95 = 80.75)
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DIAGNOSE,
                95, 95, 80, 80, 80,
                false, 10000L, 4096L, "agent_analyst", RequirementEvidence.empty()
        );

        // Candidate A: Higher capability (reasoning 78, instruction 80), but expensive ($50)
        // Its capabilityFit will be higher, but totalScore might be lower due to cost penalty
        ModelProfile strongExpensive = createModelWithTier(
                "strong-expensive", ModelTier.REASONING,
                78, 80, 70, 70, 70, BigDecimal.valueOf(50.0), BigDecimal.valueOf(150.0)
        );

        // Candidate B: Lower capability (reasoning 40, instruction 40), but extremely cheap ($0.001)
        // Due to cost score = 100, its scalar total score may be high, but capability fit is low
        ModelProfile weakCheap = createModelWithTier(
                "weak-cheap", ModelTier.REASONING,
                40, 40, 40, 40, 40, BigDecimal.valueOf(0.001), BigDecimal.valueOf(0.002)
        );

        RankingResult result = ranker.rank(req, List.of(weakCheap, strongExpensive));

        assertFalse(result.isEmpty());
        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("strong-expensive", top.model().id(),
                "No-sufficient fallback must prioritize highest capability-fit over cheap but weaker candidate");
        assertEquals("REASONING_BEST_EFFORT_HIGHEST_CAPABILITY", result.selectionReason());
    }

    @Test
    void reasoningRequest_degradesExplicitlyWhenNoReasoningModelAvailable() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DIAGNOSE,
                85, 80, 70, 50, 30,
                false, 10000L, 4096L, "agent_analyst", RequirementEvidence.empty()
        );

        // Only Fast and Balanced candidates available (no Reasoning model)
        ModelProfile fast = createModelWithTier("fast-model", ModelTier.FAST, 55, 82, 65, 80, 80, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        ModelProfile balanced = createModelWithTier("balanced-model", ModelTier.BALANCED, 75, 88, 80, 88, 88, BigDecimal.valueOf(2.00), BigDecimal.valueOf(8.00));

        RankingResult result = ranker.rank(req, List.of(fast, balanced));

        CandidateScore top = result.topCandidate().orElseThrow();
        assertEquals("balanced-model", top.model().id());
        assertEquals("NO_HARD_ELIGIBLE_REASONING_MODEL_DEGRADED", result.selectionReason());
    }

    @Test
    void sufficiency_nonCompensating_failsOnSingleDeficit() {
        // High reasoning demand (80), low coding demand (10)
        RoutingRequirement req = new RoutingRequirement(
                TaskType.ANALYZE,
                80, 80, 10, 50, 20,
                false, 10000L, 2048L, "agent_analyst", RequirementEvidence.empty()
        );

        // Model has deficit in reasoning (50 < 80 * 0.85 = 68), despite 99 coding and 99 instruction
        ModelProfile candidate = createModelWithTier("imbalanced-model", ModelTier.BALANCED, 50, 99, 99, 90, 90, BigDecimal.ONE, BigDecimal.TEN);

        WeightedModelScorer scorer = new WeightedModelScorer(new ModelScoringProperties());
        CandidateScore score = scorer.score(req, candidate, 0.01, 0.01);

        assertFalse(score.sufficient(),
                "A shortfall in demanded reasoning must not be compensated by unrelated excess coding capability");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private RoutingRequirement createReq(int reasoning, int instruction, int coding, int structured, int tool) {
        return new RoutingRequirement(
                TaskType.GENERAL_CHAT,
                reasoning, instruction, coding, structured, tool,
                false, 10000L, 2048L, "agent_analyst", RequirementEvidence.empty()
        );
    }

    private ModelProfile createModel(String id, int reasoning, int instruction, int coding, int structured, int tool, BigDecimal inPrice, BigDecimal outPrice) {
        return createModelWithTier(id, ModelTier.BALANCED, reasoning, instruction, coding, structured, tool, inPrice, outPrice);
    }

    private ModelProfile createModelWithTier(String id, ModelTier tier, int reasoning, int instruction, int coding, int structured, int tool, BigDecimal inPrice, BigDecimal outPrice) {
        return new ModelProfile(
                id, "qwen", id, tier, true,
                new ModelCapabilities(reasoning, instruction, coding, structured, tool, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(1048576L, 131072L),
                new ModelPricing(inPrice, outPrice, "CNY")
        );
    }
}
