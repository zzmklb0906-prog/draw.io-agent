package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoutingQualityEvaluatorTest {

    private RoutingQualityEvaluator evaluator;
    private BenchmarkExecutionProperties properties;

    @BeforeEach
    void setUp() {
        this.properties = new BenchmarkExecutionProperties();
        this.properties.setQualityTieTolerance(3.0);
        this.evaluator = new RoutingQualityEvaluator(properties);
    }

    @Test
    void test7_qualityRegret_calculatesDifferenceFromBestScore() {
        // Flash 70, Plus 90, Max 95. Dynamic selects Plus (90). Regret = 95 - 90 = 5.0
        BenchmarkCase bCase = createCase("c-1");
        List<BenchmarkModelResult> results = List.of(
                createResult("c-1", "qwen3.7-flash", 70.0, 0.01),
                createResult("c-1", "qwen3.7-plus", 90.0, 0.05),
                createResult("c-1", "qwen3.8-max", 95.0, 0.20)
        );

        RoutingQualityEvaluation eval = evaluator.evaluate(bCase, results, "qwen3.7-plus", "qwen3.7-flash");

        assertEquals(95.0, eval.bestQualityScore());
        assertEquals("qwen3.8-max", eval.bestQualityModel());
        assertEquals(90.0, eval.dynamicQualityScore());
        assertEquals(5.0, eval.dynamicRegret(), 0.001);
        assertEquals(25.0, eval.legacyRegret(), 0.001);
    }

    @Test
    void test8_dynamicOptimal_classifiedAsOptimalWhenSelectingBest() {
        BenchmarkCase bCase = createCase("c-2");
        List<BenchmarkModelResult> results = List.of(
                createResult("c-2", "qwen3.7-flash", 70.0, 0.01),
                createResult("c-2", "qwen3.8-max", 95.0, 0.20)
        );

        RoutingQualityEvaluation eval = evaluator.evaluate(bCase, results, "qwen3.8-max", "qwen3.7-flash");

        assertEquals(RoutingQualityClassification.OPTIMAL, eval.dynamicClassification());
        assertEquals(RoutingQualityClassification.UNDER_ROUTING, eval.legacyClassification());
        assertEquals(0.0, eval.dynamicRegret(), 0.001);
    }

    @Test
    void test9_qualityEquivalent_classifiedWhenWithinTolerance() {
        // tolerance = 3.0. Best = 95.0, Dynamic = 93.0 -> regret = 2.0 <= 3.0
        BenchmarkCase bCase = createCase("c-3");
        List<BenchmarkModelResult> results = List.of(
                createResult("c-3", "qwen3.7-plus", 93.0, 0.05),
                createResult("c-3", "qwen3.8-max", 95.0, 0.06) // costs are comparable
        );

        RoutingQualityEvaluation eval = evaluator.evaluate(bCase, results, "qwen3.7-plus", "qwen3.8-max");

        assertEquals(RoutingQualityClassification.QUALITY_EQUIVALENT, eval.dynamicClassification());
        assertEquals(2.0, eval.dynamicRegret(), 0.001);
    }

    @Test
    void test10_potentialOverRouting_flaggedWhenSelectingExpensiveModelForEquivalentQuality() {
        // Flash 93 (cost 0.01), Plus 94 (0.04), Max 95 (0.20).
        // Dynamic selects Max (0.20). Flash/Plus are quality-equivalent and much cheaper.
        BenchmarkCase bCase = createCase("c-4");
        List<BenchmarkModelResult> results = List.of(
                createResult("c-4", "qwen3.7-flash", 93.0, 0.01),
                createResult("c-4", "qwen3.7-plus", 94.0, 0.04),
                createResult("c-4", "qwen3.8-max", 95.0, 0.20)
        );

        RoutingQualityEvaluation eval = evaluator.evaluate(bCase, results, "qwen3.8-max", "qwen3.7-flash");

        assertEquals("qwen3.7-flash", eval.costEfficientBestModel());
        assertEquals(RoutingQualityClassification.POTENTIAL_OVER_ROUTING, eval.dynamicClassification());
        assertEquals(RoutingQualityClassification.QUALITY_EQUIVALENT, eval.legacyClassification());
    }

    @Test
    void test11_underRouting_flaggedWhenSelectingSignificantlyInferiorModel() {
        // Flash 55 (cost 0.01), Max 95 (cost 0.20). Dynamic selects Flash.
        BenchmarkCase bCase = createCase("c-5");
        List<BenchmarkModelResult> results = List.of(
                createResult("c-5", "qwen3.7-flash", 55.0, 0.01),
                createResult("c-5", "qwen3.8-max", 95.0, 0.20)
        );

        RoutingQualityEvaluation eval = evaluator.evaluate(bCase, results, "qwen3.7-flash", "qwen3.8-max");

        assertEquals(RoutingQualityClassification.UNDER_ROUTING, eval.dynamicClassification());
        assertEquals(40.0, eval.dynamicRegret(), 0.001);
    }

    private BenchmarkCase createCase(String caseId) {
        return new BenchmarkCase(
                caseId, TaskType.GENERAL_CHAT, "agent", "prompt",
                new BenchmarkExpectedOutput(null, List.of(), List.of(), List.of(), List.of(), null, null, null, null),
                GroundTruthLevel.DETERMINISTIC, Set.of()
        );
    }

    private BenchmarkModelResult createResult(String caseId, String modelName, double score, double cost) {
        return new BenchmarkModelResult(
                caseId, modelName, true, "response", 100L, null, cost, 100L,
                ModelQualityScore.of(score, Map.of("SCORE", score), score >= 70.0, List.of())
        );
    }
}
