package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration.CalibrationCategory;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration.CalibrationProposal;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration.ManualCalibrationAnalyzer;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration.ManualCalibrationReport;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManualCalibrationAnalyzerTest {

    private ManualCalibrationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        this.analyzer = new ManualCalibrationAnalyzer();
    }

    @Test
    void test1_requirementOverestimation_singleCaseRecordsObservation() {
        // Single case with POTENTIAL_OVER_ROUTING
        RoutingQualityEvaluation ev1 = new RoutingQualityEvaluation(
                "case-simple-1", "qwen3.8-max", "qwen3.7-plus", "qwen3.7-flash", "qwen3.7-flash",
                95.0, 95.0, 95.0, 0.0, 0.0,
                RoutingQualityClassification.POTENTIAL_OVER_ROUTING,
                RoutingQualityClassification.OPTIMAL
        );

        BenchmarkReport report = createReport(List.of(ev1), Map.of(
                "qwen3.7-flash", new ModelOverallQuality(1, 1, 1.0, 1, 1.0, 95.0, 50.0, 0.001),
                "qwen3.8-max", new ModelOverallQuality(1, 1, 1.0, 1, 1.0, 95.0, 150.0, 0.05)
        ));

        ManualCalibrationReport calibReport = analyzer.analyze(report, List.of("Human noted simple edit was over-routed"));

        assertNotNull(calibReport);
        // Single case: per Minimum Evidence Rule (>= 2 cases), no global proposal
        assertTrue(calibReport.proposals().isEmpty());
        assertTrue(calibReport.insufficientEvidenceAreas().stream()
                .anyMatch(s -> s.contains("Single over-routing case observed [case-simple-1]")));
    }

    @Test
    void test3_costWeightSignal_multipleCasesGeneratesProposal() {
        // 2 cases with POTENTIAL_OVER_ROUTING
        RoutingQualityEvaluation ev1 = new RoutingQualityEvaluation(
                "case-simple-1", "qwen3.8-max", "qwen3.7-plus", "qwen3.7-flash", "qwen3.7-flash",
                95.0, 95.0, 95.0, 0.0, 0.0,
                RoutingQualityClassification.POTENTIAL_OVER_ROUTING,
                RoutingQualityClassification.OPTIMAL
        );
        RoutingQualityEvaluation ev2 = new RoutingQualityEvaluation(
                "case-simple-2", "qwen3.8-max", "qwen3.7-plus", "qwen3.7-flash", "qwen3.7-flash",
                92.0, 92.0, 92.0, 0.0, 0.0,
                RoutingQualityClassification.POTENTIAL_OVER_ROUTING,
                RoutingQualityClassification.OPTIMAL
        );

        BenchmarkReport report = createReport(List.of(ev1, ev2), Map.of(
                "qwen3.7-flash", new ModelOverallQuality(2, 2, 1.0, 2, 1.0, 93.5, 50.0, 0.001),
                "qwen3.8-max", new ModelOverallQuality(2, 2, 1.0, 2, 1.0, 93.5, 150.0, 0.05)
        ));

        ManualCalibrationReport calibReport = analyzer.analyze(report, List.of());

        assertNotNull(calibReport);
        assertEquals(1, calibReport.proposals().size());
        CalibrationProposal prop = calibReport.proposals().get(0);
        assertEquals(CalibrationCategory.SCORING_WEIGHT, prop.category());
        assertEquals("PROP-COST-WEIGHT-001", prop.proposalId());
        assertEquals(2, prop.evidenceCases().size());
        assertTrue(prop.needsHumanApproval());
    }

    @Test
    void test4_underRouting_multipleCasesGeneratesQualityRiskProposal() {
        RoutingQualityEvaluation ev1 = new RoutingQualityEvaluation(
                "case-draw-1", "qwen3.7-flash", "qwen3.8-max", "qwen3.8-max", "qwen3.8-max",
                45.0, 95.0, 95.0, 50.0, 0.0,
                RoutingQualityClassification.UNDER_ROUTING,
                RoutingQualityClassification.OPTIMAL
        );
        RoutingQualityEvaluation ev2 = new RoutingQualityEvaluation(
                "case-draw-2", "qwen3.7-flash", "qwen3.8-max", "qwen3.8-max", "qwen3.8-max",
                50.0, 92.0, 92.0, 42.0, 0.0,
                RoutingQualityClassification.UNDER_ROUTING,
                RoutingQualityClassification.OPTIMAL
        );

        BenchmarkReport report = createReport(List.of(ev1, ev2), Map.of());

        ManualCalibrationReport calibReport = analyzer.analyze(report, List.of());

        assertNotNull(calibReport);
        assertEquals(1, calibReport.proposals().size());
        CalibrationProposal prop = calibReport.proposals().get(0);
        assertEquals(CalibrationCategory.REQUIREMENT, prop.category());
        assertEquals("PROP-UNDER-ROUTING-001", prop.proposalId());
    }

    @Test
    void test5_singleCaseInsufficientEvidence_recordsAsObservationOnly() {
        RoutingQualityEvaluation ev1 = new RoutingQualityEvaluation(
                "case-under-1", "qwen3.7-flash", "qwen3.8-max", "qwen3.8-max", "qwen3.8-max",
                45.0, 95.0, 95.0, 50.0, 0.0,
                RoutingQualityClassification.UNDER_ROUTING,
                RoutingQualityClassification.OPTIMAL
        );

        BenchmarkReport report = createReport(List.of(ev1), Map.of());

        ManualCalibrationReport calibReport = analyzer.analyze(report, List.of());

        assertTrue(calibReport.proposals().isEmpty(), "Single case must not create global proposal");
        assertTrue(calibReport.insufficientEvidenceAreas().stream().anyMatch(s -> s.contains("case-under-1")));
    }

    @Test
    void test6_providerFailureExclusion_recordsReliabilityObservation() {
        // Model with 50% success rate
        ModelOverallQuality timeoutModelQual = new ModelOverallQuality(
                4, 2, 0.50, 2, 1.0, 90.0, 3000.0, 0.02
        );

        BenchmarkReport report = createReport(List.of(), Map.of("timeout-model", timeoutModelQual));

        ManualCalibrationReport calibReport = analyzer.analyze(report, List.of());

        assertTrue(calibReport.reliabilityObservations().stream()
                .anyMatch(s -> s.contains("PROVIDER_RELIABILITY_WARNING") && s.contains("timeout-model")));
    }

    @Test
    void test7_noAutoMutation_confirmsPureAnalysis() {
        BenchmarkReport report = createReport(List.of(), Map.of());
        ManualCalibrationReport r1 = analyzer.analyze(report, List.of());
        ManualCalibrationReport r2 = analyzer.analyze(report, List.of());

        assertNotNull(r1);
        assertNotNull(r2);
        // Pure analytical service - no side effects
        assertEquals(r1.datasetId(), r2.datasetId());
    }

    private BenchmarkReport createReport(List<RoutingQualityEvaluation> evals, Map<String, ModelOverallQuality> perModel) {
        return new BenchmarkReport(
                "test-dataset",
                "v1.0.0",
                BenchmarkRunStatus.COMPLETED,
                null,
                evals.size(),
                evals.size(),
                evals.size() * 2L,
                1.0,
                perModel,
                Map.of(),
                new RouterQualitySummary(evals.size(), 0.0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0),
                new RouterQualitySummary(evals.size(), 0.0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0),
                0, 0, 0,
                evals,
                List.of(),
                Instant.now()
        );
    }
}
