package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RequirementSnapshot;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationFlag;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecord;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoutingEvaluationRecord} data representation and privacy safety.
 */
class RoutingEvaluationRecordTest {

    @Test
    void record_doesNotContainRawPromptOrSensitiveHeaders() throws Exception {
        RequirementSnapshot snapshot = new RequirementSnapshot(80, 90, 50, 88, 70, false, 10000L, 4096L);
        RoutingEvaluationRecord record = new RoutingEvaluationRecord(
                "inv-123",
                "agent_analyst",
                TaskType.DRAWIO_GENERATION,
                "qwen3.7-plus",
                "qwen3.8-max",
                false,
                93.8,
                SelectionSource.LEGACY_ROUTER,
                3,
                0,
                Map.of(),
                93.8,
                88.5,
                5.3,
                88.5,
                0.033,
                0.147,
                -0.114,
                snapshot,
                List.of(),
                Set.of(RoutingEvaluationFlag.UNMATCHED),
                Instant.now()
        );

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(record);

        // Privacy verification
        assertFalse(json.contains("latestUserText"));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("Authorization"));
        assertFalse(json.contains("baseUrl"));

        // Metric accuracy
        assertTrue(json.contains("\"matched\":false"));
        assertTrue(json.contains("\"scoreMargin\":5.3"));
        assertTrue(json.contains("\"taskType\":\"DRAWIO_GENERATION\""));
    }
}
