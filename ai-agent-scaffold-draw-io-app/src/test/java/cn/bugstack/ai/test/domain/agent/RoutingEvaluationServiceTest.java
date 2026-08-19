package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintReason;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintViolation;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelFilterResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.RejectedModel;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationFlag;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecord;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecorder;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationService;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource;
import com.google.adk.models.LlmRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoutingEvaluationService}.
 */
class RoutingEvaluationServiceTest {

    private RoutingEvaluationService evaluationService;
    private List<RoutingEvaluationRecord> recordedList;
    private ModelCatalogService catalogService;

    private ModelProfile qwenPlus;
    private ModelProfile qwenMax;
    private ModelProfile qwenFlash;

    @BeforeEach
    void setUp() {
        this.recordedList = new ArrayList<>();
        RoutingEvaluationRecorder recorder = record -> recordedList.add(record);
        WeightedModelScorer scorer = new WeightedModelScorer(new ModelScoringProperties());

        this.qwenFlash = createModel("qwen3.7-flash", BigDecimal.valueOf(0.2), BigDecimal.valueOf(0.8));
        this.qwenPlus = createModel("qwen3.7-plus", BigDecimal.valueOf(2.0), BigDecimal.valueOf(8.0));
        this.qwenMax = createModel("qwen3.8-max", BigDecimal.valueOf(12.0), BigDecimal.valueOf(36.0));

        this.catalogService = createCatalog(qwenFlash, qwenPlus, qwenMax);
        this.evaluationService = new RoutingEvaluationService(List.of(recorder), scorer, catalogService);
    }

    @Test
    void case1_legacyEqualsDynamic_reportsMatchedTrue() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);

        CandidateScore cs = createCandidateScore(qwenPlus, 92.0, 0.05);
        RankingResult ranking = new RankingResult(List.of(cs));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(qwenPlus), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", "qwen3.7-plus", true, 92.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-1", ctx, req, filterResult, ranking, comparison);

        assertTrue(record.matched());
        assertTrue(record.flags().contains(RoutingEvaluationFlag.MATCHED));
        assertEquals("qwen3.7-plus", record.actualModel());
        assertEquals("qwen3.7-plus", record.recommendedModel());
    }

    @Test
    void case2_legacyNotEqualsDynamic_reportsMatchedFalse() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.DRAWIO_GENERATION, false);

        CandidateScore csMax = createCandidateScore(qwenMax, 95.0, 0.15);
        CandidateScore csPlus = createCandidateScore(qwenPlus, 88.0, 0.03);
        RankingResult ranking = new RankingResult(List.of(csMax, csPlus));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(qwenMax, qwenPlus), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", "qwen3.8-max", false, 95.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-2", ctx, req, filterResult, ranking, comparison);

        assertFalse(record.matched());
        assertTrue(record.flags().contains(RoutingEvaluationFlag.UNMATCHED));
        assertEquals(88.0, record.actualModelScore(), 0.01);
        assertEquals(7.0, record.scoreMargin(), 0.01);
        assertNotNull(record.costDelta());
    }

    @Test
    void case3_emptyRanking_flagsNoDynamicRecommendation() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", null, null, null, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-3", ctx, req, ModelFilterResult.empty(), RankingResult.empty(), comparison);

        assertNull(record.recommendedModel());
        assertTrue(record.flags().contains(RoutingEvaluationFlag.NO_DYNAMIC_RECOMMENDATION));
        assertTrue(record.flags().contains(RoutingEvaluationFlag.NO_ELIGIBLE_CANDIDATE));
        assertTrue(record.flags().contains(RoutingEvaluationFlag.COST_COMPARISON_UNAVAILABLE));
        assertFalse(record.flags().contains(RoutingEvaluationFlag.PRICING_UNAVAILABLE),
                "No recommendation should not falsely flag PRICING_UNAVAILABLE");
    }

    @Test
    void case4_singleCandidate_scoreMarginIsNull() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);

        CandidateScore cs = createCandidateScore(qwenPlus, 90.0, 0.05);
        RankingResult ranking = new RankingResult(List.of(cs));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(qwenPlus), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", "qwen3.7-plus", true, 90.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-4", ctx, req, filterResult, ranking, comparison);

        assertEquals(90.0, record.top1Score());
        assertNull(record.top2Score());
        assertNull(record.scoreMargin(), "Single candidate must produce null scoreMargin");
    }

    @Test
    void actualModelNotInCatalog_setsCorrectFlag() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);

        RankingResult ranking = new RankingResult(List.of(createCandidateScore(qwenPlus, 90.0, 0.05)));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(qwenPlus), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("custom-external-model", "qwen3.7-plus", false, 90.0, SelectionSource.USER_EXPLICIT);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-not-in-cat", ctx, req, filterResult, ranking, comparison);

        assertTrue(record.flags().contains(RoutingEvaluationFlag.ACTUAL_MODEL_NOT_IN_CATALOG));
        assertFalse(record.flags().contains(RoutingEvaluationFlag.ACTUAL_MODEL_HARD_REJECTED));
        assertTrue(record.flags().contains(RoutingEvaluationFlag.COST_COMPARISON_UNAVAILABLE));
        assertFalse(record.flags().contains(RoutingEvaluationFlag.PRICING_UNAVAILABLE),
                "Model not in catalog should set ACTUAL_MODEL_NOT_IN_CATALOG, not PRICING_UNAVAILABLE");
        assertNull(record.estimatedActualCost());
        assertNull(record.costDelta());
    }

    @Test
    void hardRejectedActualModel_stillCalculatesCostWhenCatalogPricingExists() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, true);

        // qwen3.7-plus is in catalog and has pricing
        ModelProfile rejectedModel = qwenPlus;
        ModelProfile acceptedModel = qwenMax;

        List<RejectedModel> rejectedList = List.of(new RejectedModel(rejectedModel, List.of(
                new ConstraintViolation(ConstraintReason.VISION_UNSUPPORTED, "vision", "no vision")
        )));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(acceptedModel), rejectedList, List.of());
        RankingResult ranking = new RankingResult(List.of(createCandidateScore(acceptedModel, 95.0, 0.15)));
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", "qwen3.8-max", false, 95.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-hard-reject", ctx, req, filterResult, ranking, comparison);

        assertTrue(record.flags().contains(RoutingEvaluationFlag.ACTUAL_MODEL_HARD_REJECTED));
        assertFalse(record.flags().contains(RoutingEvaluationFlag.ACTUAL_MODEL_NOT_IN_CATALOG));
        assertNull(record.actualModelScore(), "Hard rejected model should have null actualModelScore");
        assertNotNull(record.estimatedActualCost(), "Hard rejected model with pricing in catalog must produce estimatedActualCost");
        assertNotNull(record.estimatedRecommendedCost());
        assertNotNull(record.costDelta(), "Cost delta must be computed when both costs exist");
        assertFalse(record.flags().contains(RoutingEvaluationFlag.PRICING_UNAVAILABLE));
        assertFalse(record.flags().contains(RoutingEvaluationFlag.COST_COMPARISON_UNAVAILABLE));
    }

    @Test
    void actualModelPricingMissing_setsPricingAndComparisonUnavailable() {
        ModelProfile modelNoPrice = new ModelProfile("no-price-model", "test", "no-price-model", true,
                new ModelCapabilities(80, 80, 80, 80, 80, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(100000L, 8192L),
                null);

        ModelCatalogService localCatalog = createCatalog(qwenPlus, qwenMax, modelNoPrice);
        RoutingEvaluationService localService = new RoutingEvaluationService(List.of(), new WeightedModelScorer(new ModelScoringProperties()), localCatalog);

        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);
        RankingResult ranking = new RankingResult(List.of(createCandidateScore(qwenPlus, 90.0, 0.05)));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(qwenPlus), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("no-price-model", "qwen3.7-plus", false, 90.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = localService.buildRecord("inv-price-miss", ctx, req, filterResult, ranking, comparison);

        assertTrue(record.flags().contains(RoutingEvaluationFlag.PRICING_UNAVAILABLE));
        assertTrue(record.flags().contains(RoutingEvaluationFlag.COST_COMPARISON_UNAVAILABLE));
        assertNull(record.estimatedActualCost());
        assertNull(record.costDelta());
        assertFalse(record.flags().contains(RoutingEvaluationFlag.ACTUAL_MODEL_NOT_IN_CATALOG));
    }

    @Test
    void recommendedPricingMissing_setsPricingAndComparisonUnavailable() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);
        ModelProfile recModelNoPrice = new ModelProfile("rec-no-price", "test", "rec-no-price", true,
                new ModelCapabilities(80, 80, 80, 80, 80, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(100000L, 8192L),
                null);

        CandidateScore cs = createCandidateScore(recModelNoPrice, 90.0, -1.0);
        RankingResult ranking = new RankingResult(List.of(cs));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(recModelNoPrice), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", "rec-no-price", false, 90.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-rec-miss", ctx, req, filterResult, ranking, comparison);

        assertTrue(record.flags().contains(RoutingEvaluationFlag.PRICING_UNAVAILABLE));
        assertTrue(record.flags().contains(RoutingEvaluationFlag.COST_COMPARISON_UNAVAILABLE));
        assertNull(record.estimatedRecommendedCost());
        assertNull(record.costDelta());
    }

    @Test
    void catalogModelMatchingByModelName_findsProfile() {
        // Model whose id is "qwen-plus-id" but modelName is "qwen3.7-plus"
        ModelProfile customNamed = new ModelProfile("qwen-plus-id", "test", "qwen3.7-plus", true,
                new ModelCapabilities(80, 80, 80, 80, 80, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(100000L, 8192L),
                new ModelPricing(BigDecimal.ONE, BigDecimal.TEN, "CNY"));

        ModelCatalogService localCatalog = createCatalog(customNamed);
        RoutingEvaluationService localService = new RoutingEvaluationService(List.of(), new WeightedModelScorer(new ModelScoringProperties()), localCatalog);

        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);
        RankingResult ranking = new RankingResult(List.of(createCandidateScore(customNamed, 90.0, 0.05)));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(customNamed), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", "qwen3.7-plus", true, 90.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = localService.buildRecord("inv-match-name", ctx, req, filterResult, ranking, comparison);

        assertFalse(record.flags().contains(RoutingEvaluationFlag.ACTUAL_MODEL_NOT_IN_CATALOG));
        assertNotNull(record.estimatedActualCost());
    }

    @Test
    void catalogLookupFailure_doesNotMarkActualModelNotInCatalog() {
        ModelCatalogService brokenCatalog = new ModelCatalogService(new ModelCatalogProperties()) {
            @Override
            public Optional<ModelProfile> findByModelName(String modelName) {
                throw new RuntimeException("Catalog DB Connection Timeout");
            }
            @Override
            public Optional<ModelProfile> findById(String id) {
                throw new RuntimeException("Catalog DB Connection Timeout");
            }
        };

        RoutingEvaluationService localService = new RoutingEvaluationService(List.of(), new WeightedModelScorer(new ModelScoringProperties()), brokenCatalog);
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);

        RankingResult ranking = new RankingResult(List.of(createCandidateScore(qwenPlus, 90.0, 0.05)));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(qwenPlus), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", "qwen3.7-plus", true, 90.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = localService.buildRecord("inv-lookup-fail", ctx, req, filterResult, ranking, comparison);

        assertTrue(record.flags().contains(RoutingEvaluationFlag.CATALOG_LOOKUP_FAILED),
                "Catalog lookup exception must be flagged as CATALOG_LOOKUP_FAILED");
        assertFalse(record.flags().contains(RoutingEvaluationFlag.ACTUAL_MODEL_NOT_IN_CATALOG),
                "Lookup failure must NOT be falsely flagged as ACTUAL_MODEL_NOT_IN_CATALOG");
        assertFalse(record.flags().contains(RoutingEvaluationFlag.PRICING_UNAVAILABLE),
                "Lookup failure must NOT be falsely flagged as PRICING_UNAVAILABLE");
        assertTrue(record.flags().contains(RoutingEvaluationFlag.COST_COMPARISON_UNAVAILABLE),
                "Cost comparison should be unavailable when actual cost cannot be computed");
        assertNull(record.estimatedActualCost());
        assertNull(record.costDelta());
    }

    @Test
    void tryRecord_recorderThrowsException_failsSilentlyWithoutThrowing() {
        RoutingEvaluationRecorder brokenRecorder = record -> {
            throw new RuntimeException("DB Connection Timeout");
        };
        RoutingEvaluationService resilientService = new RoutingEvaluationService(List.of(brokenRecorder), new WeightedModelScorer(new ModelScoringProperties()), catalogService);

        RoutingEvaluationRecord dummyRecord = new RoutingEvaluationRecord(
                "inv-test", "agent", TaskType.GENERAL_CHAT, "m", "m", true, 90.0, SelectionSource.LEGACY_ROUTER,
                1, 0, java.util.Map.of(), 90.0, null, null, 90.0, 0.05, 0.05, 0.0, null, List.of(), Set.of(), java.time.Instant.now()
        );

        assertDoesNotThrow(() -> resilientService.tryRecord(dummyRecord),
                "tryRecord must isolate recorder exceptions from callers");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ModelCatalogService createCatalog(ModelProfile... profiles) {
        Map<String, ModelProfile> byId = new HashMap<>();
        Map<String, ModelProfile> byName = new HashMap<>();
        for (ModelProfile p : profiles) {
            byId.put(p.id().toLowerCase(), p);
            byName.put(p.modelName().toLowerCase(), p);
        }
        return new ModelCatalogService(new ModelCatalogProperties()) {
            @Override
            public Optional<ModelProfile> findById(String id) {
                return Optional.ofNullable(id != null ? byId.get(id.toLowerCase()) : null);
            }
            @Override
            public Optional<ModelProfile> findByModelName(String modelName) {
                return Optional.ofNullable(modelName != null ? byName.get(modelName.toLowerCase()) : null);
            }
            @Override
            public List<ModelProfile> getAllModels() {
                return List.of(profiles);
            }
            @Override
            public List<ModelProfile> getEnabledModels() {
                return Arrays.stream(profiles).filter(ModelProfile::enabled).toList();
            }
        };
    }

    private RoutingContext createContext(String agent) {
        return new RoutingContext(LlmRequest.builder().model("test").build(), "text", 100, 10L, agent, "UNKNOWN", false, null);
    }

    private RoutingRequirement createReq(TaskType taskType, boolean vision) {
        return new RoutingRequirement(taskType, 80, 80, 80, 80, 80, vision, 10000L, 4096L, "agent", RequirementEvidence.empty());
    }

    private ModelProfile createModel(String id, BigDecimal inPrice, BigDecimal outPrice) {
        return new ModelProfile(id, "qwen", id, true,
                new ModelCapabilities(80, 80, 80, 80, 80, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(1048576L, 131072L),
                new ModelPricing(inPrice, outPrice, "CNY"));
    }

    private CandidateScore createCandidateScore(ModelProfile model, double totalScore, double cost) {
        return new CandidateScore(model, totalScore, cost, new ScoreBreakdown(totalScore, 100, 100, 100, 100, 100, 100, 100, 100, 0, List.of()));
    }
}
