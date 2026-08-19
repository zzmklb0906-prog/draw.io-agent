package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCapabilities;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelFeatures;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelLimits;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelPricing;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.catalog.SupportStatus;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.CandidateScore;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScoringProperties;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.WeightedModelScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WeightedModelScorer} (Phase 5 Dynamic Model Scoring).
 */
class WeightedModelScorerTest {

    private WeightedModelScorer scorer;

    @BeforeEach
    void setUp() {
        this.scorer = new WeightedModelScorer(new ModelScoringProperties());
    }

    // =========================================================================
    // Case 1 & 2 & 3 & 4: Capability Fit Edge Cases
    // =========================================================================

    @Test
    void case1_exactCapabilityMatch_yieldsHundredFit() {
        RoutingRequirement req = createReq(80, 80, 80, 80, 80);
        ModelProfile model = createModel("m1", 80, 80, 80, 80, 80, BigDecimal.ONE, BigDecimal.TEN);

        CandidateScore score = scorer.score(req, model, 0.01, 0.01);

        assertEquals(100.0, score.breakdown().capabilityFit(), 0.01);
    }

    @Test
    void case2_capabilitySurplus_cappedAtHundredFitWithoutBonus() {
        RoutingRequirement req = createReq(30, 30, 30, 30, 30);
        ModelProfile strongModel = createModel("strong", 95, 95, 95, 95, 95, BigDecimal.ONE, BigDecimal.TEN);

        CandidateScore score = scorer.score(req, strongModel, 0.01, 0.01);

        assertEquals(100.0, score.breakdown().capabilityFit(), 0.01,
                "Capability surplus must be capped at 100 without unbounded bonus");
    }

    @Test
    void case3_capabilityDeficit_proportionallyDecreasesFit() {
        RoutingRequirement req = createReq(80, 80, 80, 80, 80);
        ModelProfile weakModel = createModel("weak", 40, 40, 40, 40, 40, BigDecimal.ONE, BigDecimal.TEN);

        CandidateScore score = scorer.score(req, weakModel, 0.01, 0.01);

        assertEquals(25.0, score.breakdown().capabilityFit(), 0.01);
    }

    @Test
    void case4_zeroRequirement_yieldsHundredFitWithoutDivideByZero() {
        RoutingRequirement req = createReq(0, 80, 0, 80, 0);
        ModelProfile model = createModel("m", 50, 80, 50, 80, 50, BigDecimal.ONE, BigDecimal.TEN);

        CandidateScore score = scorer.score(req, model, 0.01, 0.01);

        assertEquals(100.0, score.breakdown().reasoningFit(), 0.01);
        assertEquals(100.0, score.breakdown().codingFit(), 0.01);
        assertEquals(100.0, score.breakdown().toolCallingFit(), 0.01);
        assertEquals(100.0, score.breakdown().capabilityFit(), 0.01);
    }

    @Test
    void case5_allSoftRequirementsZero_yieldsHundredFitWithoutNaN() {
        RoutingRequirement req = createReq(0, 0, 0, 0, 0);
        ModelProfile model = createModel("m", 50, 50, 50, 50, 50, BigDecimal.ONE, BigDecimal.TEN);

        CandidateScore score = scorer.score(req, model, 0.01, 0.01);

        assertFalse(Double.isNaN(score.totalScore()));
        assertEquals(100.0, score.breakdown().capabilityFit(), 0.01);
    }

    @Test
    void case6_scoreAlwaysClampedBetweenZeroAndHundred() {
        RoutingRequirement req = createReq(100, 100, 100, 100, 100);
        ModelProfile model = createModel("m", 99, 99, 99, 99, 99, BigDecimal.ONE, BigDecimal.TEN);

        CandidateScore score = scorer.score(req, model, 0.0, 10.0);

        assertTrue(score.totalScore() >= 0.0 && score.totalScore() <= 100.0);
    }

    // =========================================================================
    // Preference & Behavior Scenarios
    // =========================================================================

    @Test
    void simpleTask_costPreferenceFavorsCheaperAdequateModel() {
        // Simple edit: low requirement (30)
        RoutingRequirement req = createReq(30, 30, 10, 30, 10);

        // Cheap model: adequate capability (60), very cheap
        ModelProfile cheapModel = createModel("cheap", 60, 60, 60, 60, 60, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        // Expensive model: high capability (95), very expensive
        ModelProfile expensiveModel = createModel("expensive", 95, 95, 95, 95, 95, BigDecimal.valueOf(12.00), BigDecimal.valueOf(36.00));

        double costCheap = scorer.estimateCost(req, cheapModel);
        double costExpensive = scorer.estimateCost(req, expensiveModel);

        CandidateScore scoreCheap = scorer.score(req, cheapModel, costCheap, costExpensive);
        CandidateScore scoreExpensive = scorer.score(req, expensiveModel, costCheap, costExpensive);

        // Both have 100 capability fit, but cheapModel has higher costScore -> higher totalScore
        assertEquals(100.0, scoreCheap.breakdown().capabilityFit(), 0.01);
        assertEquals(100.0, scoreExpensive.breakdown().capabilityFit(), 0.01);
        assertTrue(scoreCheap.totalScore() > scoreExpensive.totalScore(),
                "Adequate cheaper model must score higher on simple low-requirement tasks");
    }

    @Test
    void complexTask_qualityPriorityFavorsStrongerModel() {
        // High reasoning & instruction demand
        RoutingRequirement req = createReq(95, 95, 80, 80, 80);

        // Weak cheap model: severely misses capability (55 reasoning)
        ModelProfile cheapWeakModel = createModel("cheapWeak", 55, 70, 50, 50, 50, BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.80));
        // Strong expensive model: highly matches capability (92 reasoning, 95 instruction)
        ModelProfile strongModel = createModel("strong", 92, 95, 90, 90, 90, BigDecimal.valueOf(12.00), BigDecimal.valueOf(36.00));

        double costCheap = scorer.estimateCost(req, cheapWeakModel);
        double costStrong = scorer.estimateCost(req, strongModel);

        CandidateScore scoreWeak = scorer.score(req, cheapWeakModel, costCheap, costStrong);
        CandidateScore scoreStrong = scorer.score(req, strongModel, costCheap, costStrong);

        assertTrue(scoreStrong.totalScore() > scoreWeak.totalScore(),
                "Strong model must outscore underqualified model on complex high-requirement tasks");
    }

    @Test
    void drawerTask_structuredAndToolSpecializationFavored() {
        // Drawer requirement: extreme structured output (98) and high tool calling (88)
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DRAWIO_GENERATION,
                65, 90, 50, 98, 88,
                false, 10000L, 16384L, "agent_drawer", RequirementEvidence.empty()
        );

        // Model A: High reasoning (95), but moderate structured (75) and tool (70)
        ModelProfile modelA = createModel("modelA", 95, 85, 80, 75, 70, BigDecimal.valueOf(2.0), BigDecimal.valueOf(8.0));
        // Model B: Moderate reasoning (75), but high structured (98) and tool (90)
        ModelProfile modelB = createModel("modelB", 75, 90, 70, 98, 90, BigDecimal.valueOf(2.0), BigDecimal.valueOf(8.0));

        CandidateScore scoreA = scorer.score(req, modelA, 0.05, 0.05);
        CandidateScore scoreB = scorer.score(req, modelB, 0.05, 0.05);

        assertTrue(scoreB.totalScore() > scoreA.totalScore(),
                "Model specialized in structured output and tool calling must score higher for drawer task");
    }

    @Test
    void codingTask_prefersHighCodingCapabilityModel() {
        // High coding demand
        RoutingRequirement req = new RoutingRequirement(
                TaskType.CODE_GENERATION,
                80, 70, 95, 40, 20,
                false, 10000L, 4096L, "agent_analyst", RequirementEvidence.empty()
        );

        // Model A: high coding (95), reasoning 85
        ModelProfile modelA = createModel("modelA", 85, 80, 95, 60, 50, BigDecimal.valueOf(2.0), BigDecimal.valueOf(8.0));
        // Model B: low coding (40), reasoning 95
        ModelProfile modelB = createModel("modelB", 95, 90, 40, 60, 50, BigDecimal.valueOf(2.0), BigDecimal.valueOf(8.0));

        CandidateScore scoreA = scorer.score(req, modelA, 0.05, 0.05);
        CandidateScore scoreB = scorer.score(req, modelB, 0.05, 0.05);

        assertTrue(scoreA.totalScore() > scoreB.totalScore(),
                "Model with high coding capability must outscore model with low coding on code-heavy tasks");
    }

    @Test
    void visionUnknown_receivesUncertaintyPenalty_withoutHardRejection() {
        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT,
                60, 60, 60, 60, 60,
                true, // visionRequired = true
                10000L, 2048L, "agent_analyst", RequirementEvidence.empty()
        );

        // Model 1: Vision SUPPORTED
        ModelProfile supportedModel = createModelWithVision("supp", SupportStatus.SUPPORTED);
        // Model 2: Vision UNKNOWN
        ModelProfile unknownModel = createModelWithVision("unkn", SupportStatus.UNKNOWN);

        CandidateScore scoreSupp = scorer.score(req, supportedModel, 0.01, 0.01);
        CandidateScore scoreUnkn = scorer.score(req, unknownModel, 0.01, 0.01);

        assertTrue(scoreSupp.totalScore() > scoreUnkn.totalScore(),
                "Model with SUPPORTED vision must outscore model with UNKNOWN vision");
        assertTrue(scoreUnkn.totalScore() > 0.0, "UNKNOWN vision must NOT receive zero score (not rejected)");
        assertEquals(10.0, scoreUnkn.breakdown().uncertaintyPenalty(), 0.01);
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
        return new ModelProfile(
                id, "qwen", id, true,
                new ModelCapabilities(reasoning, instruction, coding, structured, tool, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(1048576L, 131072L),
                new ModelPricing(inPrice, outPrice, "CNY")
        );
    }

    private ModelProfile createModelWithVision(String id, SupportStatus visionStatus) {
        return new ModelProfile(
                id, "qwen", id, true,
                new ModelCapabilities(80, 80, 80, 80, 80, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, visionStatus),
                new ModelLimits(1048576L, 131072L),
                new ModelPricing(BigDecimal.ONE, BigDecimal.TEN, "CNY")
        );
    }
}
