package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Collects {@link ModelExecutionResult} telemetries and coordinates profile updates into {@link ModelRuntimeProfileStore}.
 */
@Slf4j
@Component
public class RuntimeFeedbackCollector {

    private final RuntimeMetricsAggregator aggregator;
    private final ModelRuntimeProfileStore profileStore;
    private final RuntimeFeedbackProperties properties;

    public RuntimeFeedbackCollector(RuntimeMetricsAggregator aggregator,
                                    ModelRuntimeProfileStore profileStore,
                                    RuntimeFeedbackProperties properties) {
        this.aggregator = aggregator;
        this.profileStore = profileStore;
        this.properties = properties != null ? properties : new RuntimeFeedbackProperties();
    }

    /**
     * Collects and processes an execution result telemetry event.
     */
    public RuntimeFeedback collect(ModelExecutionResult result) {
        if (result == null || !properties.isEnabled()) {
            return null;
        }

        String modelId = StringUtils.isNotBlank(result.modelId()) ? result.modelId() : result.modelName();
        if (StringUtils.isBlank(modelId)) {
            log.warn("Skipping runtime feedback: modelId and modelName are blank");
            return null;
        }

        RuntimeFeedback feedback = new RuntimeFeedback(
                result.requestId(),
                modelId,
                result.success(),
                result.latencyMs(),
                result.failureType(),
                result.errorMessage(),
                result.inputTokens(),
                result.outputTokens(),
                result.timestamp()
        );

        // Ingest into aggregator
        aggregator.ingest(feedback);

        // Generate and update profile snapshot into store
        if (profileStore != null) {
            ModelRuntimeProfile updatedProfile = aggregator.generateProfile(modelId);
            profileStore.save(updatedProfile);
            log.debug("Updated runtime profile for model [{}]: successRate={}, health={}, samples={}",
                    modelId, updatedProfile.successRate(), updatedProfile.health(), updatedProfile.sampleCount());
        }

        return feedback;
    }
}
