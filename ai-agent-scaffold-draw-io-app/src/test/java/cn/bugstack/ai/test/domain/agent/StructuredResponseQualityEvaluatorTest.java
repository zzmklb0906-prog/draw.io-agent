package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StructuredResponseQualityEvaluatorTest {

    private StructuredResponseQualityEvaluator evaluator;

    @BeforeEach
    void setUp() {
        this.evaluator = new StructuredResponseQualityEvaluator();
    }

    @Test
    void evaluate_validJsonWithAllRequiredFields_returnsHighScoreAndPassed() {
        BenchmarkExpectedOutput expected = new BenchmarkExpectedOutput(
                "{}",
                List.of("name", "age", "role"),
                List.of(), List.of(), List.of(), null, null, null, null
        );
        BenchmarkCase bCase = new BenchmarkCase(
                "c-json-1", TaskType.STRUCTURED_GENERATION, "agent", "Generate user JSON",
                expected, GroundTruthLevel.DETERMINISTIC, Set.of()
        );

        BenchmarkRawResponse resp = BenchmarkRawResponse.success(
                "{\"name\": \"Alice\", \"age\": 30, \"role\": \"developer\"}",
                120L, 50L, 20L, 70L
        );

        ModelQualityScore score = evaluator.evaluate(bCase, resp);

        assertTrue(score.passed());
        assertEquals(100.0, score.totalScore(), 0.001);
        assertEquals(40.0, score.dimensions().get("JSON_SYNTAX"));
        assertEquals(40.0, score.dimensions().get("FIELD_COMPLETENESS"));
        assertEquals(20.0, score.dimensions().get("FORMAT_COMPLIANCE"));
    }

    @Test
    void evaluate_missingRequiredField_reducesScore() {
        BenchmarkExpectedOutput expected = new BenchmarkExpectedOutput(
                "{}",
                List.of("name", "age", "role"),
                List.of(), List.of(), List.of(), null, null, null, null
        );
        BenchmarkCase bCase = new BenchmarkCase(
                "c-json-2", TaskType.STRUCTURED_GENERATION, "agent", "Generate user JSON",
                expected, GroundTruthLevel.DETERMINISTIC, Set.of()
        );

        // Missing "role" (2 out of 3 found -> 40 * 2/3 = 26.66)
        BenchmarkRawResponse resp = BenchmarkRawResponse.success(
                "{\"name\": \"Alice\", \"age\": 30}",
                120L, 50L, 20L, 70L
        );

        ModelQualityScore score = evaluator.evaluate(bCase, resp);

        assertFalse(score.passed());
        assertTrue(score.issues().contains("Missing required field: role"));
        assertEquals(86.666, score.totalScore(), 0.01);
    }

    @Test
    void evaluate_invalidJsonSyntax_failsCompletely() {
        BenchmarkExpectedOutput expected = new BenchmarkExpectedOutput(
                "{}", List.of("name"), List.of(), List.of(), List.of(), null, null, null, null
        );
        BenchmarkCase bCase = new BenchmarkCase(
                "c-json-3", TaskType.STRUCTURED_GENERATION, "agent", "Generate user JSON",
                expected, GroundTruthLevel.DETERMINISTIC, Set.of()
        );

        BenchmarkRawResponse resp = BenchmarkRawResponse.success(
                "{invalid json here", 100L, null, null, null
        );

        ModelQualityScore score = evaluator.evaluate(bCase, resp);

        assertFalse(score.passed());
        assertEquals(0.0, score.totalScore(), 0.001);
        assertEquals(0.0, score.dimensions().get("JSON_SYNTAX"));
    }
}
