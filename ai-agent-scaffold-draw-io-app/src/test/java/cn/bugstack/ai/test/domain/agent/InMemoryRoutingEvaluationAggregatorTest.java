package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.InMemoryRoutingEvaluationAggregator;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecord;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationSummary;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InMemoryRoutingEvaluationAggregator}.
 */
class InMemoryRoutingEvaluationAggregatorTest {

    private InMemoryRoutingEvaluationAggregator aggregator;

    @BeforeEach
    void setUp() {
        this.aggregator = new InMemoryRoutingEvaluationAggregator();
    }

    @Test
    void record_aggregatesMetricsAccurately() {
        // Record 1: Matched
        aggregator.record(createRecord("inv-1", "agent_analyst", TaskType.GENERAL_CHAT, "qwen3.7-flash", "qwen3.7-flash", true));
        // Record 2: Unmatched
        aggregator.record(createRecord("inv-2", "agent_drawer", TaskType.DRAWIO_GENERATION, "qwen3.7-plus", "qwen3.8-max", false));
        // Record 3: No recommendation
        aggregator.record(createRecord("inv-3", "agent_analyst", TaskType.DIAGNOSE, "qwen3.7-plus", null, null));

        RoutingEvaluationSummary summary = aggregator.getSummary();

        assertEquals(3, summary.totalInvocations());
        assertEquals(2, summary.comparableInvocations());
        assertEquals(1, summary.agreementCount());
        assertEquals(1, summary.disagreementCount());
        assertEquals(1, summary.noRecommendationCount());
        assertEquals(0.5, summary.agreementRate(), 0.001);

        assertEquals(2L, summary.byAgent().get("agent_analyst"));
        assertEquals(1L, summary.byAgent().get("agent_drawer"));
        assertEquals(1L, summary.byTaskType().get(TaskType.DRAWIO_GENERATION));
        assertEquals(1L, summary.byRecommendedModel().get("qwen3.8-max"));
        assertEquals(2L, summary.byActualModel().get("qwen3.7-plus"));
    }

    @Test
    void reset_clearsAllAggregatedData() {
        aggregator.record(createRecord("inv-1", "agent_analyst", TaskType.GENERAL_CHAT, "qwen3.7-flash", "qwen3.7-flash", true));
        assertEquals(1, aggregator.getSummary().totalInvocations());

        aggregator.reset();
        assertEquals(0, aggregator.getSummary().totalInvocations());
        assertTrue(aggregator.getSummary().byTaskType().isEmpty());
    }

    private RoutingEvaluationRecord createRecord(String invId, String agent, TaskType taskType, String actual, String recommended, Boolean matched) {
        return new RoutingEvaluationRecord(
                invId, agent, taskType, actual, recommended, matched, 90.0, SelectionSource.LEGACY_ROUTER,
                3, 0, Map.of(), 90.0, 80.0, 10.0, 90.0, 0.05, 0.05, 0.0, null, List.of(), Set.of(), Instant.now()
        );
    }
}
