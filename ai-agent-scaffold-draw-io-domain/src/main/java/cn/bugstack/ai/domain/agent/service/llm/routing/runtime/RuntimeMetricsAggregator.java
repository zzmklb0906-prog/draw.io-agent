package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Aggregates incoming {@link RuntimeFeedback} telemetry events into {@link ModelRuntimeProfile} snapshots.
 *
 * <p><strong>Architectural Guardrail:</strong>
 * Aggregation is distinct from online weight learning. This component computes factual
 * statistics (success rate, average latency, timeout rate) without modifying ranking weights.</p>
 */
@Component
public class RuntimeMetricsAggregator {

    private final RuntimeHealthEvaluator healthEvaluator;
    private final Map<String, ModelStatsAccumulator> statsMap = new ConcurrentHashMap<>();

    public RuntimeMetricsAggregator(RuntimeHealthEvaluator healthEvaluator) {
        this.healthEvaluator = healthEvaluator != null ? healthEvaluator : new RuntimeHealthEvaluator();
    }

    public RuntimeMetricsAggregator() {
        this(new RuntimeHealthEvaluator());
    }

    /**
     * Ingests a new runtime feedback telemetry event.
     */
    public void ingest(RuntimeFeedback feedback) {
        if (feedback == null || StringUtils.isBlank(feedback.modelId())) {
            return;
        }
        String modelKey = feedback.modelId().trim().toLowerCase();
        statsMap.computeIfAbsent(modelKey, k -> new ModelStatsAccumulator(modelKey))
                .record(feedback);
    }

    /**
     * Generates a point-in-time immutable snapshot of {@link ModelRuntimeProfile} for a given model.
     */
    public ModelRuntimeProfile generateProfile(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return ModelRuntimeProfile.unknown("unknown");
        }
        String modelKey = modelId.trim().toLowerCase();
        ModelStatsAccumulator acc = statsMap.get(modelKey);
        if (acc == null || acc.sampleCount.get() == 0) {
            return ModelRuntimeProfile.unknown(modelId);
        }

        long count = acc.sampleCount.get();
        long successes = acc.successCount.get();
        long timeouts = acc.timeoutCount.get();
        double totalLatency = acc.totalLatencyMs.sum();

        double successRate = Math.round(((double) successes / count) * 1000.0) / 1000.0;
        double timeoutRate = Math.round(((double) timeouts / count) * 1000.0) / 1000.0;
        double avgLatency = Math.round((totalLatency / count) * 10.0) / 10.0;

        RuntimeHealth health = healthEvaluator.evaluate(successRate, timeoutRate, count);

        return new ModelRuntimeProfile(
                modelId,
                successRate,
                timeoutRate,
                avgLatency,
                null,
                health,
                count
        );
    }

    /**
     * Batch aggregates a list of feedback events for deterministic offline evaluation.
     */
    public ModelRuntimeProfile aggregateBatch(String modelId, List<RuntimeFeedback> feedbacks) {
        if (feedbacks == null || feedbacks.isEmpty()) {
            return ModelRuntimeProfile.unknown(modelId);
        }

        long count = 0;
        long successes = 0;
        long timeouts = 0;
        double totalLatency = 0;

        for (RuntimeFeedback f : feedbacks) {
            if (f == null) continue;
            count++;
            if (f.success()) successes++;
            if (f.failureType() == FailureType.TIMEOUT) timeouts++;
            totalLatency += f.latencyMs();
        }

        if (count == 0) {
            return ModelRuntimeProfile.unknown(modelId);
        }

        double successRate = Math.round(((double) successes / count) * 1000.0) / 1000.0;
        double timeoutRate = Math.round(((double) timeouts / count) * 1000.0) / 1000.0;
        double avgLatency = Math.round((totalLatency / count) * 10.0) / 10.0;

        RuntimeHealth health = healthEvaluator.evaluate(successRate, timeoutRate, count);

        return new ModelRuntimeProfile(
                modelId,
                successRate,
                timeoutRate,
                avgLatency,
                null,
                health,
                count
        );
    }

    private static class ModelStatsAccumulator {
        final String modelId;
        final AtomicLong sampleCount = new AtomicLong(0);
        final AtomicLong successCount = new AtomicLong(0);
        final AtomicLong timeoutCount = new AtomicLong(0);
        final DoubleAdder totalLatencyMs = new DoubleAdder();

        ModelStatsAccumulator(String modelId) {
            this.modelId = modelId;
        }

        void record(RuntimeFeedback feedback) {
            sampleCount.incrementAndGet();
            if (feedback.success()) {
                successCount.incrementAndGet();
            }
            if (feedback.failureType() == FailureType.TIMEOUT) {
                timeoutCount.incrementAndGet();
            }
            totalLatencyMs.add(feedback.latencyMs());
        }
    }
}
