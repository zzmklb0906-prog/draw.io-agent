package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

/**
 * Model Dynamic Runtime Profile.
 *
 * <p>Encapsulates observational runtime performance metrics (latency, reliability, health, sample count)
 * distinct from static {@link cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile} metadata.</p>
 */
public record ModelRuntimeProfile(
        String modelId,
        double successRate,
        double timeoutRate,
        double averageLatencyMs,
        Double estimatedCostPerRequest,
        RuntimeHealth health,
        long sampleCount
) {
    public static ModelRuntimeProfile unknown(String modelId) {
        return new ModelRuntimeProfile(modelId, 0.95, 0.0, 1000.0, null, RuntimeHealth.UNKNOWN, 0L);
    }

    public static ModelRuntimeProfile healthy(String modelId, double successRate, double timeoutRate, double avgLatencyMs) {
        return new ModelRuntimeProfile(modelId, successRate, timeoutRate, avgLatencyMs, null, RuntimeHealth.HEALTHY, 100L);
    }
}
