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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoutingEvaluationService}.
 */
class RoutingEvaluationServiceTest {

    private RoutingEvaluationService evaluationService;
    private List<RoutingEvaluationRecord> recordedList;

    @BeforeEach
    void setUp() {
        this.recordedList = new ArrayList<>();
        RoutingEvaluationRecorder recorder = record -> recordedList.add(record);
        WeightedModelScorer scorer = new WeightedModelScorer(new ModelScoringProperties());
        this.evaluationService = new RoutingEvaluationService(List.of(recorder), scorer);
    }

    @Test
    void case1_legacyEqualsDynamic_reportsMatchedTrue() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);
        ModelProfile model = createModel("qwen3.7-plus", BigDecimal.ONE, BigDecimal.TEN);

        CandidateScore cs = createCandidateScore(model, 92.0, 0.05);
        RankingResult ranking = new RankingResult(List.of(cs));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(model), List.of(), List.of());
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
        ModelProfile modelMax = createModel("qwen3.8-max", BigDecimal.valueOf(12.0), BigDecimal.valueOf(36.0));
        ModelProfile modelPlus = createModel("qwen3.7-plus", BigDecimal.valueOf(2.0), BigDecimal.valueOf(8.0));

        CandidateScore csMax = createCandidateScore(modelMax, 95.0, 0.15);
        CandidateScore csPlus = createCandidateScore(modelPlus, 88.0, 0.03);
        RankingResult ranking = new RankingResult(List.of(csMax, csPlus));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(modelMax, modelPlus), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("qwen3.7-plus", "qwen3.8-max", false, 95.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-2", ctx, req, filterResult, ranking, comparison);

        assertFalse(record.matched());
        assertTrue(record.flags().contains(RoutingEvaluationFlag.UNMATCHED));
        assertEquals(88.0, record.actualModelScore(), 0.01);
        assertEquals(7.0, record.scoreMargin(), 0.01);
        assertEquals(0.15 - 0.03, record.costDelta(), 0.0001);
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
    }

    @Test
    void case4_singleCandidate_scoreMarginIsNull() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);
        ModelProfile model = createModel("only-model", BigDecimal.ONE, BigDecimal.TEN);

        CandidateScore cs = createCandidateScore(model, 90.0, 0.05);
        RankingResult ranking = new RankingResult(List.of(cs));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(model), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("only-model", "only-model", true, 90.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-4", ctx, req, filterResult, ranking, comparison);

        assertEquals(90.0, record.top1Score());
        assertNull(record.top2Score());
        assertNull(record.scoreMargin(), "Single candidate must produce null scoreMargin");
    }

    @Test
    void case5_actualModelHardRejected_isFlaggedAppropriately() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, true);
        ModelProfile acceptedModel = createModel("vision-model", BigDecimal.ONE, BigDecimal.TEN);
        ModelProfile rejectedModel = createModel("legacy-text-model", BigDecimal.ONE, BigDecimal.TEN);

        List<RejectedModel> rejectedList = List.of(new RejectedModel(rejectedModel, List.of(
                new ConstraintViolation(ConstraintReason.VISION_UNSUPPORTED, "vision", "no vision")
        )));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(acceptedModel), rejectedList, List.of());
        RankingResult ranking = new RankingResult(List.of(createCandidateScore(acceptedModel, 90.0, 0.05)));
        RoutingShadowComparison comparison = new RoutingShadowComparison("legacy-text-model", "vision-model", false, 90.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-5", ctx, req, filterResult, ranking, comparison);

        assertTrue(record.flags().contains(RoutingEvaluationFlag.ACTUAL_MODEL_HARD_REJECTED));
        assertEquals(1, record.rejectedCandidateCount());
    }

    @Test
    void case6_missingPricing_costDeltaIsNullWithoutSynthesizedZero() {
        RoutingContext ctx = createContext("agent_analyst");
        RoutingRequirement req = createReq(TaskType.GENERAL_CHAT, false);
        ModelProfile modelNoPrice = new ModelProfile("no-price", "qwen", "no-price", true,
                new ModelCapabilities(80, 80, 80, 80, 80, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(100000L, 8192L),
                null);

        CandidateScore cs = createCandidateScore(modelNoPrice, 90.0, -1.0);
        RankingResult ranking = new RankingResult(List.of(cs));
        ModelFilterResult filterResult = new ModelFilterResult(List.of(modelNoPrice), List.of(), List.of());
        RoutingShadowComparison comparison = new RoutingShadowComparison("no-price", "no-price", true, 90.0, SelectionSource.LEGACY_ROUTER);

        RoutingEvaluationRecord record = evaluationService.buildRecord("inv-6", ctx, req, filterResult, ranking, comparison);

        assertNull(record.costDelta());
        assertTrue(record.flags().contains(RoutingEvaluationFlag.PRICING_UNAVAILABLE));
    }

    @Test
    void tryRecord_recorderThrowsException_failsSilentlyWithoutThrowing() {
        RoutingEvaluationRecorder brokenRecorder = record -> {
            throw new RuntimeException("DB Connection Timeout");
        };
        RoutingEvaluationService resilientService = new RoutingEvaluationService(List.of(brokenRecorder), new WeightedModelScorer(new ModelScoringProperties()));

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
