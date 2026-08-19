package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe In-Memory Aggregator for Shadow Routing telemetry metrics.
 */
@Component
public class InMemoryRoutingEvaluationAggregator implements RoutingEvaluationRecorder {

    private final LongAdder totalInvocations = new LongAdder();
    private final LongAdder agreementCount = new LongAdder();
    private final LongAdder disagreementCount = new LongAdder();
    private final LongAdder noRecommendationCount = new LongAdder();

    private final ConcurrentHashMap<TaskType, LongAdder> byTaskType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> byAgent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> byRecommendedModel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> byActualModel = new ConcurrentHashMap<>();

    @Override
    public void record(RoutingEvaluationRecord record) {
        if (record == null) return;
        totalInvocations.increment();

        if (record.matched() == null || record.recommendedModel() == null) {
            noRecommendationCount.increment();
        } else if (Boolean.TRUE.equals(record.matched())) {
            agreementCount.increment();
        } else {
            disagreementCount.increment();
        }

        if (record.taskType() != null) {
            byTaskType.computeIfAbsent(record.taskType(), k -> new LongAdder()).increment();
        }
        if (record.agentName() != null) {
            byAgent.computeIfAbsent(record.agentName(), k -> new LongAdder()).increment();
        }
        if (record.recommendedModel() != null) {
            byRecommendedModel.computeIfAbsent(record.recommendedModel(), k -> new LongAdder()).increment();
        }
        if (record.actualModel() != null) {
            byActualModel.computeIfAbsent(record.actualModel(), k -> new LongAdder()).increment();
        }
    }

    public RoutingEvaluationSummary getSummary() {
        long total = totalInvocations.sum();
        long agree = agreementCount.sum();
        long disagree = disagreementCount.sum();
        long noRec = noRecommendationCount.sum();
        long comparable = agree + disagree;
        double rate = comparable > 0 ? (double) agree / comparable : 0.0;

        Map<TaskType, Long> taskTypeMap = new HashMap<>();
        byTaskType.forEach((k, v) -> taskTypeMap.put(k, v.sum()));

        Map<String, Long> agentMap = new HashMap<>();
        byAgent.forEach((k, v) -> agentMap.put(k, v.sum()));

        Map<String, Long> recModelMap = new HashMap<>();
        byRecommendedModel.forEach((k, v) -> recModelMap.put(k, v.sum()));

        Map<String, Long> actModelMap = new HashMap<>();
        byActualModel.forEach((k, v) -> actModelMap.put(k, v.sum()));

        return new RoutingEvaluationSummary(
                total,
                comparable,
                agree,
                disagree,
                noRec,
                rate,
                Map.copyOf(taskTypeMap),
                Map.copyOf(agentMap),
                Map.copyOf(recModelMap),
                Map.copyOf(actModelMap)
        );
    }

    public void reset() {
        totalInvocations.reset();
        agreementCount.reset();
        disagreementCount.reset();
        noRecommendationCount.reset();
        byTaskType.clear();
        byAgent.clear();
        byRecommendedModel.clear();
        byActualModel.clear();
    }
}
