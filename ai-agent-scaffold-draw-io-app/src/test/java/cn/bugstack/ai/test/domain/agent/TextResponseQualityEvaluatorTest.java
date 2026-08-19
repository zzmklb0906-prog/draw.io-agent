package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TextResponseQualityEvaluatorTest {

    private TextResponseQualityEvaluator evaluator;

    @BeforeEach
    void setUp() {
        this.evaluator = new TextResponseQualityEvaluator();
    }

    @Test
    void evaluate_textTaskWithRequiredFacts_passesAccurately() {
        BenchmarkExpectedOutput expected = new BenchmarkExpectedOutput(
                null, List.of("NPE", "NullPointerException", "User.getAddress()"),
                List.of("StackOverflowError"), List.of(), List.of(), null, null, null, null
        );
        BenchmarkCase bCase = new BenchmarkCase(
                "c-diag-1", TaskType.DIAGNOSE, "agent_analyst", "Diagnose NPE log",
                expected, GroundTruthLevel.RULE_BASED, Set.of()
        );

        BenchmarkRawResponse resp = BenchmarkRawResponse.success(
                "The root cause is a NPE (NullPointerException) triggered when invoking User.getAddress() on a null instance.",
                100L, null, null, null
        );

        ModelQualityScore score = evaluator.evaluate(bCase, resp);

        assertTrue(score.passed());
        assertEquals(100.0, score.totalScore(), 0.001);
        assertEquals(20.0, score.dimensions().get("VALIDITY"));
        assertEquals(50.0, score.dimensions().get("FACT_COVERAGE"));
        assertEquals(30.0, score.dimensions().get("FACT_ACCURACY"));
    }

    @Test
    void evaluate_textTaskWithForbiddenTerm_penalizesAccuracy() {
        BenchmarkExpectedOutput expected = new BenchmarkExpectedOutput(
                null, List.of("NPE"),
                List.of("StackOverflowError"), List.of(), List.of(), null, null, null, null
        );
        BenchmarkCase bCase = new BenchmarkCase(
                "c-diag-2", TaskType.DIAGNOSE, "agent_analyst", "Diagnose NPE log",
                expected, GroundTruthLevel.RULE_BASED, Set.of()
        );

        BenchmarkRawResponse resp = BenchmarkRawResponse.success(
                "The error indicates a StackOverflowError caused by recursive calls.",
                100L, null, null, null
        );

        ModelQualityScore score = evaluator.evaluate(bCase, resp);

        assertFalse(score.passed());
        assertTrue(score.issues().stream().anyMatch(i -> i.contains("Found forbidden term")));
    }
}
