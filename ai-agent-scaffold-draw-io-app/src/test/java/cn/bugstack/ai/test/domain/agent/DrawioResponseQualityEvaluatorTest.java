package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DrawioResponseQualityEvaluatorTest {

    private DrawioResponseQualityEvaluator evaluator;

    @BeforeEach
    void setUp() {
        this.evaluator = new DrawioResponseQualityEvaluator();
    }

    @Test
    void evaluate_validDrawioXml_returnsHighScoreAndPassed() {
        BenchmarkExpectedOutput expected = new BenchmarkExpectedOutput(
                null, List.of(), List.of(), List.of("Start", "Process"), List.of(), 2, 1, null, null
        );
        BenchmarkCase bCase = new BenchmarkCase(
                "c-drawio-1", TaskType.DRAWIO_GENERATION, "agent_drawer", "Generate Draw.io XML",
                expected, GroundTruthLevel.DETERMINISTIC, Set.of()
        );

        String validXml = """
                <mxGraphModel>
                  <root>
                    <mxCell id="0"/>
                    <mxCell id="1" parent="0"/>
                    <mxCell id="2" value="Start" vertex="1" parent="1"/>
                    <mxCell id="3" value="Process" vertex="1" parent="1"/>
                    <mxCell id="4" value="Flow" edge="1" source="2" target="3" parent="1"/>
                  </root>
                </mxGraphModel>
                """;

        BenchmarkRawResponse resp = BenchmarkRawResponse.success(validXml, 200L, 100L, 50L, 150L);
        ModelQualityScore score = evaluator.evaluate(bCase, resp);

        assertTrue(score.passed());
        assertEquals(100.0, score.totalScore(), 0.001);
        assertEquals(30.0, score.dimensions().get("XML_PARSING"));
        assertEquals(20.0, score.dimensions().get("GRAPH_STRUCTURE"));
        assertEquals(20.0, score.dimensions().get("CELL_INTEGRITY"));
        assertEquals(15.0, score.dimensions().get("EDGE_VALIDITY"));
        assertEquals(15.0, score.dimensions().get("ELEMENT_COMPLETENESS"));
    }

    @Test
    void evaluate_invalidDrawioWithDuplicateIdAndDanglingEdge_reducesScore() {
        BenchmarkExpectedOutput expected = new BenchmarkExpectedOutput(
                null, List.of(), List.of(), List.of("Start"), List.of(), 2, 1, null, null
        );
        BenchmarkCase bCase = new BenchmarkCase(
                "c-drawio-2", TaskType.DRAWIO_GENERATION, "agent_drawer", "Generate Draw.io XML",
                expected, GroundTruthLevel.DETERMINISTIC, Set.of()
        );

        // id "2" duplicated, edge targets non-existent id "99"
        String badXml = """
                <mxGraphModel>
                  <root>
                    <mxCell id="0"/>
                    <mxCell id="1" parent="0"/>
                    <mxCell id="2" value="Start" vertex="1" parent="1"/>
                    <mxCell id="2" value="Duplicate" vertex="1" parent="1"/>
                    <mxCell id="4" value="Flow" edge="1" source="2" target="99" parent="1"/>
                  </root>
                </mxGraphModel>
                """;

        BenchmarkRawResponse resp = BenchmarkRawResponse.success(badXml, 150L, null, null, null);
        ModelQualityScore score = evaluator.evaluate(bCase, resp);

        assertFalse(score.passed());
        assertTrue(score.issues().stream().anyMatch(i -> i.contains("Duplicate mxCell id")));
        assertTrue(score.issues().stream().anyMatch(i -> i.contains("Dangling edge target reference")));
        assertTrue(score.totalScore() < 85.0);
    }
}
