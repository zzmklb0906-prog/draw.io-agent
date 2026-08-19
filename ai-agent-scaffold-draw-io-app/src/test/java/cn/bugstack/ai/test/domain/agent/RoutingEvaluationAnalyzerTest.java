package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintReason;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RankedCandidateSnapshot;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RequirementSnapshot;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationFlag;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecord;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoutingEvaluationAnalyzer} (Phase 7).
 */
class RoutingEvaluationAnalyzerTest {

    private RoutingEvaluationAnalyzer analyzer;
    private RoutingEvaluationAnalysisProperties properties;

    @BeforeEach
    void setUp() {
        this.properties = new RoutingEvaluationAnalysisProperties();
        this.properties.setMinSampleSize(10);
        this.analyzer = new RoutingEvaluationAnalyzer(properties);
    }

    // =========================================================================
    // Test 1: Agreement Statistics
    // =========================================================================

    @Test
    void test1_agreementStatistics_calculatesAccurately() {
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            records.add(createRecord("inv-" + i, "agent_analyst", TaskType.GENERAL_CHAT, "qwen3.7-plus", "qwen3.7-plus", true, 5.0, 0.0));
        }
        for (int i = 6; i < 10; i++) {
            records.add(createRecord("inv-" + i, "agent_analyst", TaskType.GENERAL_CHAT, "qwen3.7-plus", "qwen3.8-max", false, 5.0, 0.05));
        }

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);

        assertEquals(10, report.totalRecords());
        assertEquals(10, report.comparableRecords());
        assertEquals(6, report.agreementCount());
        assertEquals(4, report.disagreementCount());
        assertEquals(0.60, report.agreementRate(), 0.001);
    }

    // =========================================================================
    // Test 2: No Recommendation Excluded from Agreement Denominator
    // =========================================================================

    @Test
    void test2_noRecommendation_excludedFromAgreementDenominator() {
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            records.add(createRecord("inv-" + i, "agent_analyst", TaskType.GENERAL_CHAT, "qwen3.7-plus", "qwen3.7-plus", true, 5.0, 0.0));
        }
        for (int i = 6; i < 10; i++) {
            records.add(createRecord("inv-" + i, "agent_analyst", TaskType.GENERAL_CHAT, "qwen3.7-plus", "qwen3.8-max", false, 5.0, 0.05));
        }
        // 5 records with no recommendation
        for (int i = 10; i < 15; i++) {
            records.add(createRecord("inv-" + i, "agent_analyst", TaskType.GENERAL_CHAT, "qwen3.7-plus", null, null, null, null));
        }

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);

        assertEquals(15, report.totalRecords());
        assertEquals(10, report.comparableRecords(), "No recommendation must NOT count as comparable");
        assertEquals(5, report.noRecommendationCount());
        assertEquals(0.60, report.agreementRate(), 0.001);
    }

    // =========================================================================
    // Test 3: Model Distribution
    // =========================================================================

    @Test
    void test3_modelDistribution_countsAccurately() {
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) records.add(createRecord("inv-f" + i, "agent", TaskType.SIMPLE_EDIT, "qwen3.7-plus", "qwen3.7-flash", false, 10.0, -0.01));
        for (int i = 0; i < 3; i++) records.add(createRecord("inv-p" + i, "agent", TaskType.DRAWIO_GENERATION, "qwen3.7-plus", "qwen3.7-plus", true, 5.0, 0.0));
        for (int i = 0; i < 2; i++) records.add(createRecord("inv-m" + i, "agent", TaskType.DIAGNOSE, "qwen3.7-plus", "qwen3.8-max", false, 8.0, 0.10));

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);

        assertEquals(5L, report.recommendedModelDistribution().get("qwen3.7-flash"));
        assertEquals(3L, report.recommendedModelDistribution().get("qwen3.7-plus"));
        assertEquals(2L, report.recommendedModelDistribution().get("qwen3.8-max"));
        assertEquals(10L, report.actualModelDistribution().get("qwen3.7-plus"));
    }

    // =========================================================================
    // Test 4: TaskType Model Matrix & Cost Delta
    // =========================================================================

    @Test
    void test4_taskTypeModelMatrix_aggregatesPerTaskType() {
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        // 5 SIMPLE_EDIT
        for (int i = 0; i < 5; i++) records.add(createRecord("inv-s" + i, "agent", TaskType.SIMPLE_EDIT, "qwen3.7-plus", "qwen3.7-flash", false, 10.0, -0.02));
        // 5 DIAGNOSE
        for (int i = 0; i < 5; i++) records.add(createRecord("inv-d" + i, "agent", TaskType.DIAGNOSE, "qwen3.7-plus", "qwen3.8-max", false, 8.0, 0.10));

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);

        TaskTypeAnalysis simpleAnalysis = report.taskTypeAnalysis().get(TaskType.SIMPLE_EDIT);
        assertNotNull(simpleAnalysis);
        assertEquals(5L, simpleAnalysis.totalCount());
        assertEquals(5L, simpleAnalysis.recommendedModelDistribution().get("qwen3.7-flash"));
        assertEquals(-0.02, simpleAnalysis.averageCostDelta(), 0.001);

        TaskTypeAnalysis diagAnalysis = report.taskTypeAnalysis().get(TaskType.DIAGNOSE);
        assertNotNull(diagAnalysis);
        assertEquals(5L, diagAnalysis.totalCount());
        assertEquals(5L, diagAnalysis.recommendedModelDistribution().get("qwen3.8-max"));
        assertEquals(0.10, diagAnalysis.averageCostDelta(), 0.001);
    }

    // =========================================================================
    // Test 5: Agent Model Matrix
    // =========================================================================

    @Test
    void test5_agentModelMatrix_aggregatesPerAgent() {
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        for (int i = 0; i < 6; i++) records.add(createRecord("inv-a" + i, "agent_analyst", TaskType.ANALYZE, "qwen3.7-plus", "qwen3.7-plus", true, 5.0, 0.0));
        for (int i = 0; i < 4; i++) records.add(createRecord("inv-b" + i, "agent_drawer", TaskType.DRAWIO_GENERATION, "qwen3.7-plus", "qwen3.8-max", false, 8.0, 0.10));

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);

        AgentAnalysis analyst = report.agentAnalysis().get("agent_analyst");
        assertNotNull(analyst);
        assertEquals(6L, analyst.totalCount());
        assertEquals(1.0, analyst.agreementRate(), 0.001);

        AgentAnalysis drawer = report.agentAnalysis().get("agent_drawer");
        assertNotNull(drawer);
        assertEquals(4L, drawer.totalCount());
        assertEquals(0.0, drawer.agreementRate(), 0.001);
    }

    // =========================================================================
    // Test 6: Score Margin Statistics (Average, Median, Percentiles)
    // =========================================================================

    @Test
    void test6_scoreMarginStatistics_calculatesPercentilesCorrectly() {
        // Margins: 1.0, 2.0, 3.0, 4.0, 10.0 (low margin threshold = 5.0 -> 4 low margin)
        List<RoutingEvaluationRecord> records = List.of(
                createRecord("1", "a", TaskType.GENERAL_CHAT, "m", "m", true, 1.0, 0.0),
                createRecord("2", "a", TaskType.GENERAL_CHAT, "m", "m", true, 2.0, 0.0),
                createRecord("3", "a", TaskType.GENERAL_CHAT, "m", "m", true, 3.0, 0.0),
                createRecord("4", "a", TaskType.GENERAL_CHAT, "m", "m", true, 4.0, 0.0),
                createRecord("5", "a", TaskType.GENERAL_CHAT, "m", "m", true, 10.0, 0.0)
        );

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);
        ScoreMarginStatistics marginStats = report.scoreMarginStatistics();

        assertEquals(5L, marginStats.sampleCount());
        assertEquals(4.0, marginStats.average(), 0.001);
        assertEquals(3.0, marginStats.median(), 0.001);
        assertEquals(2.0, marginStats.p25(), 0.001);
        assertEquals(4.0, marginStats.p75(), 0.001);
        assertEquals(7.6, marginStats.p90(), 0.001);
        assertEquals(4L, marginStats.lowMarginCount());
        assertEquals(0.80, marginStats.lowMarginRate(), 0.001);
    }

    // =========================================================================
    // Test 7: Null Margin Exclusion
    // =========================================================================

    @Test
    void test7_nullMargin_excludedFromMarginStatistics() {
        List<RoutingEvaluationRecord> records = List.of(
                createRecord("1", "a", TaskType.GENERAL_CHAT, "m", "m", true, 6.0, 0.0),
                createRecord("2", "a", TaskType.GENERAL_CHAT, "m", "m", true, 10.0, 0.0),
                createRecord("3", "a", TaskType.GENERAL_CHAT, "m", "m", true, null, 0.0) // null margin
        );

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);
        ScoreMarginStatistics marginStats = report.scoreMarginStatistics();

        assertEquals(2L, marginStats.sampleCount());
        assertEquals(8.0, marginStats.average(), 0.001);
        assertEquals(8.0, marginStats.median(), 0.001);
    }

    // =========================================================================
    // Test 8: Cost Delta Statistics
    // =========================================================================

    @Test
    void test8_costDeltaStatistics_calculatesCategoriesCorrectly() {
        // Cost deltas: -0.10, -0.03, 0.0, 0.05, 0.20
        List<RoutingEvaluationRecord> records = List.of(
                createRecord("1", "a", TaskType.GENERAL_CHAT, "m", "m", true, 5.0, -0.10),
                createRecord("2", "a", TaskType.GENERAL_CHAT, "m", "m", true, 5.0, -0.03),
                createRecord("3", "a", TaskType.GENERAL_CHAT, "m", "m", true, 5.0, 0.0),
                createRecord("4", "a", TaskType.GENERAL_CHAT, "m", "m", true, 5.0, 0.05),
                createRecord("5", "a", TaskType.GENERAL_CHAT, "m", "m", true, 5.0, 0.20)
        );

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);
        CostDeltaStatistics costStats = report.costDeltaStatistics();

        assertEquals(5L, costStats.comparableCount());
        assertEquals(0.024, costStats.averageCostDelta(), 0.001);
        assertEquals(0.0, costStats.medianCostDelta(), 0.001);
        assertEquals(2L, costStats.recommendedCheaperCount());
        assertEquals(1L, costStats.sameEstimatedCostCount());
        assertEquals(2L, costStats.recommendedMoreExpensiveCount());
        assertEquals(0.40, costStats.cheaperRate(), 0.001);
        assertEquals(0.20, costStats.sameCostRate(), 0.001);
        assertEquals(0.40, costStats.moreExpensiveRate(), 0.001);
    }

    // =========================================================================
    // Test 9: Null Cost Exclusion
    // =========================================================================

    @Test
    void test9_nullCost_excludedFromCostStatistics() {
        List<RoutingEvaluationRecord> records = List.of(
                createRecord("1", "a", TaskType.GENERAL_CHAT, "m", "m", true, 5.0, 0.10),
                createRecord("2", "a", TaskType.GENERAL_CHAT, "m", "m", true, 5.0, null) // null cost delta
        );

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);
        CostDeltaStatistics costStats = report.costDeltaStatistics();

        assertEquals(1L, costStats.comparableCount());
        assertEquals(0.10, costStats.averageCostDelta(), 0.001);
    }

    // =========================================================================
    // Test 10: Hard Rejection Distribution
    // =========================================================================

    @Test
    void test10_hardRejectionDistribution_countsAccurately() {
        RoutingEvaluationRecord r1 = new RoutingEvaluationRecord(
                "inv-1", "a", TaskType.GENERAL_CHAT, "m", "m", true, 90.0, SelectionSource.LEGACY_ROUTER,
                1, 2, Map.of("m2", List.of(ConstraintReason.VISION_UNSUPPORTED), "m3", List.of(ConstraintReason.CONTEXT_WINDOW_TOO_SMALL)),
                90.0, null, null, 90.0, 0.05, 0.05, 0.0, null, List.of(), Set.of(RoutingEvaluationFlag.ACTUAL_MODEL_HARD_REJECTED), Instant.now()
        );
        RoutingEvaluationRecord r2 = new RoutingEvaluationRecord(
                "inv-2", "a", TaskType.GENERAL_CHAT, "m", "m", true, 90.0, SelectionSource.LEGACY_ROUTER,
                1, 1, Map.of("m2", List.of(ConstraintReason.VISION_UNSUPPORTED)),
                90.0, null, null, 90.0, 0.05, 0.05, 0.0, null, List.of(), Set.of(), Instant.now()
        );

        RoutingEvaluationAnalysisReport report = analyzer.analyze(List.of(r1, r2));

        assertEquals(2L, report.hardRejectionReasonDistribution().get(ConstraintReason.VISION_UNSUPPORTED));
        assertEquals(1L, report.hardRejectionReasonDistribution().get(ConstraintReason.CONTEXT_WINDOW_TOO_SMALL));
        assertEquals(1L, report.actualHardRejectedCount());
        assertEquals(0.50, report.actualHardRejectedRate(), 0.001);
    }

    // =========================================================================
    // Test 11: Requirement Statistics
    // =========================================================================

    @Test
    void test11_requirementStatistics_aggregatesDimensionsAndHighDemandRates() {
        RequirementSnapshot req1 = new RequirementSnapshot(90, 80, 85, 95, 70, false, 10000L, 4096L);
        RequirementSnapshot req2 = new RequirementSnapshot(70, 80, 60, 90, 60, false, 10000L, 4096L);

        RoutingEvaluationRecord r1 = createRecordWithReq("1", req1);
        RoutingEvaluationRecord r2 = createRecordWithReq("2", req2);

        RoutingEvaluationAnalysisReport report = analyzer.analyze(List.of(r1, r2));
        RequirementDimensionStatistics reqStats = report.requirementStatistics();

        assertEquals(2L, reqStats.sampleCount());
        assertEquals(80.0, reqStats.avgReasoning(), 0.001);
        assertEquals(80.0, reqStats.avgInstructionFollowing(), 0.001);
        assertEquals(72.5, reqStats.avgCoding(), 0.001);
        assertEquals(92.5, reqStats.avgStructuredOutput(), 0.001);
        assertEquals(65.0, reqStats.avgToolCalling(), 0.001);

        // High demand (>=85): reasoning=1/2 (50%), coding=1/2 (50%), structuredOutput=2/2 (100%), tool=0/2 (0%)
        assertEquals(0.50, reqStats.highDemandReasoningRate(), 0.001);
        assertEquals(0.50, reqStats.highDemandCodingRate(), 0.001);
        assertEquals(1.00, reqStats.highDemandStructuredOutputRate(), 0.001);
        assertEquals(0.00, reqStats.highDemandToolCallingRate(), 0.001);
    }

    // =========================================================================
    // Test 12: Pair Competition (Top1 vs Top2)
    // =========================================================================

    @Test
    void test12_pairCompetition_ordersByCountAndCalculatesMargin() {
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        // max > plus 3 times with margins 5.0, 7.0, 9.0 (avg margin = 7.0)
        records.add(createRecordWithCandidates("1", List.of("qwen3.8-max", "qwen3.7-plus"), 5.0));
        records.add(createRecordWithCandidates("2", List.of("qwen3.8-max", "qwen3.7-plus"), 7.0));
        records.add(createRecordWithCandidates("3", List.of("qwen3.8-max", "qwen3.7-plus"), 9.0));

        // plus > flash 2 times with margins 3.0, 5.0 (avg margin = 4.0)
        records.add(createRecordWithCandidates("4", List.of("qwen3.7-plus", "qwen3.7-flash"), 3.0));
        records.add(createRecordWithCandidates("5", List.of("qwen3.7-plus", "qwen3.7-flash"), 5.0));

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);
        List<ModelCompetitionPair> pairs = report.modelCompetitionPairs();

        assertEquals(2, pairs.size());
        assertEquals("qwen3.8-max", pairs.get(0).top1Model());
        assertEquals("qwen3.7-plus", pairs.get(0).top2Model());
        assertEquals(3L, pairs.get(0).count());
        assertEquals(7.0, pairs.get(0).averageMargin(), 0.001);

        assertEquals("qwen3.7-plus", pairs.get(1).top1Model());
        assertEquals("qwen3.7-flash", pairs.get(1).top2Model());
        assertEquals(2L, pairs.get(1).count());
        assertEquals(4.0, pairs.get(1).averageMargin(), 0.001);
    }

    // =========================================================================
    // Test 13: Input Order Independence (Determinism)
    // =========================================================================

    @Test
    void test13_inputOrderIndependence_producesIdenticalReport() {
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            records.add(createRecord("inv-" + i, i % 2 == 0 ? "agent_a" : "agent_b",
                    i % 3 == 0 ? TaskType.SIMPLE_EDIT : TaskType.DRAWIO_GENERATION,
                    "qwen3.7-plus", i % 2 == 0 ? "qwen3.7-flash" : "qwen3.8-max", i % 4 == 0,
                    (double) (i + 1), (i % 2 == 0 ? -0.01 : 0.05)));
        }

        RoutingEvaluationAnalysisReport r1 = analyzer.analyze(records);

        List<RoutingEvaluationRecord> shuffled = new ArrayList<>(records);
        Collections.shuffle(shuffled, new Random(42));
        RoutingEvaluationAnalysisReport r2 = analyzer.analyze(shuffled);

        assertEquals(r1.totalRecords(), r2.totalRecords());
        assertEquals(r1.agreementCount(), r2.agreementCount());
        assertEquals(r1.agreementRate(), r2.agreementRate(), 0.0001);
        assertEquals(r1.recommendedModelDistribution(), r2.recommendedModelDistribution());
        assertEquals(r1.scoreMarginStatistics().average(), r2.scoreMarginStatistics().average(), 0.0001);
        assertEquals(r1.scoreMarginStatistics().median(), r2.scoreMarginStatistics().median(), 0.0001);
        assertEquals(r1.costDeltaStatistics().averageCostDelta(), r2.costDeltaStatistics().averageCostDelta(), 0.0001);
    }

    // =========================================================================
    // Test 14: Empty Dataset Safety
    // =========================================================================

    @Test
    void test14_emptyDataset_returnsSafeEmptyReportWithoutExceptions() {
        RoutingEvaluationAnalysisReport report = analyzer.analyze(List.of());

        assertEquals(0, report.totalRecords());
        assertEquals(0, report.comparableRecords());
        assertEquals(0.0, report.agreementRate());
        assertTrue(report.insufficientSample());
        assertTrue(report.recommendedModelDistribution().isEmpty());
        assertNull(report.scoreMarginStatistics().average());
        assertNull(report.costDeltaStatistics().averageCostDelta());
    }

    // =========================================================================
    // Test 15: Insufficient Sample Safety
    // =========================================================================

    @Test
    void test15_insufficientSample_flagsInsufficientAndAvoidsAggressiveRecommendations() {
        // minSampleSize is 10, provide only 5 records
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(createRecord("inv-" + i, "agent", TaskType.GENERAL_CHAT, "qwen3.7-plus", "qwen3.8-max", false, 1.0, 0.10));
        }

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);

        assertTrue(report.insufficientSample());
        assertEquals(1, report.recommendations().size());
        assertEquals("INSUFFICIENT_SAMPLE", report.recommendations().get(0).code());
    }

    // =========================================================================
    // Test 16: Recommendation Is Purely Advisory
    // =========================================================================

    @Test
    void test16_recommendations_generateAdvisorySuggestionsWhenThresholdsExceeded() {
        // Build 12 records (>= minSampleSize 10) with high cost increase and low margins
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            RequirementSnapshot req = new RequirementSnapshot(95, 80, 80, 95, 80, false, 10000L, 4096L);
            records.add(new RoutingEvaluationRecord(
                    "inv-" + i, "agent", TaskType.DRAWIO_GENERATION, "qwen3.7-plus", "qwen3.8-max", false, 95.0, SelectionSource.LEGACY_ROUTER,
                    2, 1, Map.of("flash", List.of(ConstraintReason.VISION_UNSUPPORTED)),
                    95.0, 94.0, 1.0, 85.0, 0.20, 0.05, 0.15, req,
                    List.of(new RankedCandidateSnapshot("qwen3.8-max", 95.0, 95.0, 0.20), new RankedCandidateSnapshot("qwen3.7-plus", 94.0, 90.0, 0.05)),
                    Set.of(RoutingEvaluationFlag.UNMATCHED, RoutingEvaluationFlag.LOW_SCORE_MARGIN), Instant.now()
            ));
        }

        RoutingEvaluationAnalysisReport report = analyzer.analyze(records);

        assertFalse(report.insufficientSample());
        assertFalse(report.recommendations().isEmpty());

        List<String> codes = report.recommendations().stream().map(RoutingCalibrationRecommendation::code).toList();
        assertTrue(codes.contains("HIGH_COST_INCREASE_RATE"), "Should advise checking cost preference");
        assertTrue(codes.contains("LOW_SCORE_MARGIN_CONCENTRATION"), "Should advise checking candidate separation");
        assertTrue(codes.contains("REQUIREMENT_DIMENSION_SATURATION"), "Should advise checking structured output requirement saturation");

        // Verify none of the recommendations contain automatic configuration change commands
        for (RoutingCalibrationRecommendation rec : report.recommendations()) {
            assertNotNull(rec.suggestedInvestigation());
            assertFalse(rec.suggestedInvestigation().contains("setCapabilityWeight"));
            assertFalse(rec.suggestedInvestigation().contains("setCostWeight"));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private RoutingEvaluationRecord createRecord(String invId, String agent, TaskType taskType, String actual, String rec, Boolean matched, Double margin, Double costDelta) {
        Double estRecCost = costDelta != null ? 0.10 + costDelta : null;
        Double estActCost = costDelta != null ? 0.10 : null;
        Set<RoutingEvaluationFlag> flags = new HashSet<>();
        if (Boolean.TRUE.equals(matched)) flags.add(RoutingEvaluationFlag.MATCHED);
        else if (Boolean.FALSE.equals(matched)) flags.add(RoutingEvaluationFlag.UNMATCHED);
        if (rec == null) flags.add(RoutingEvaluationFlag.NO_DYNAMIC_RECOMMENDATION);
        if (costDelta == null) flags.add(RoutingEvaluationFlag.COST_COMPARISON_UNAVAILABLE);

        return new RoutingEvaluationRecord(
                invId, agent, taskType, actual, rec, matched, 90.0, SelectionSource.LEGACY_ROUTER,
                1, 0, Map.of(), 90.0, margin != null ? 90.0 - margin : null, margin, 90.0,
                estRecCost, estActCost, costDelta, null, List.of(), flags, Instant.now()
        );
    }

    private RoutingEvaluationRecord createRecordWithReq(String invId, RequirementSnapshot req) {
        return new RoutingEvaluationRecord(
                invId, "agent", TaskType.GENERAL_CHAT, "qwen3.7-plus", "qwen3.7-plus", true, 90.0, SelectionSource.LEGACY_ROUTER,
                1, 0, Map.of(), 90.0, null, null, 90.0, 0.05, 0.05, 0.0, req, List.of(), Set.of(RoutingEvaluationFlag.MATCHED), Instant.now()
        );
    }

    private RoutingEvaluationRecord createRecordWithCandidates(String invId, List<String> candidateIds, Double margin) {
        List<RankedCandidateSnapshot> top = candidateIds.stream()
                .map(id -> new RankedCandidateSnapshot(id, 90.0, 90.0, 0.05))
                .toList();
        return new RoutingEvaluationRecord(
                invId, "agent", TaskType.GENERAL_CHAT, "qwen3.7-plus", candidateIds.get(0), true, 90.0, SelectionSource.LEGACY_ROUTER,
                2, 0, Map.of(), 90.0, margin != null ? 90.0 - margin : null, margin, 90.0,
                0.05, 0.05, 0.0, null, top, Set.of(RoutingEvaluationFlag.MATCHED), Instant.now()
        );
    }
}
