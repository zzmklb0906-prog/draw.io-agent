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
}
