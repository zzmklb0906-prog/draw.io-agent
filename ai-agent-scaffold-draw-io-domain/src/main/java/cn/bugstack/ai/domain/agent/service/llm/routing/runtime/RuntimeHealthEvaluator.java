package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import org.springframework.stereotype.Component;

/**
 * Evaluates {@link RuntimeHealth} based on accumulated observational metrics.
 */
@Component
public class RuntimeHealthEvaluator {

    private final RuntimeFeedbackProperties properties;

    public RuntimeHealthEvaluator(RuntimeFeedbackProperties properties) {
        this.properties = properties != null ? properties : new RuntimeFeedbackProperties();
    }

    public RuntimeHealthEvaluator() {
        this(new RuntimeFeedbackProperties());
    }

    /**
     * Evaluates model health status from aggregated metrics.
     *
     * @param successRate success rate (0.0 to 1.0)
     * @param timeoutRate timeout rate (0.0 to 1.0)
     * @param sampleCount total number of observational samples
     * @return evaluated {@link RuntimeHealth}
     */
    public RuntimeHealth evaluate(double successRate, double timeoutRate, long sampleCount) {
        // Insufficient statistical samples -> UNKNOWN (do not penalize new models prematurely)
        if (sampleCount < properties.getMinSamplesForHealth()) {
            return RuntimeHealth.UNKNOWN;
        }

        if (successRate >= properties.getHealthySuccessThreshold() && timeoutRate <= properties.getMaxHealthyTimeoutRate()) {
            return RuntimeHealth.HEALTHY;
        } else if (successRate >= properties.getDegradedSuccessThreshold()) {
            return RuntimeHealth.DEGRADED;
        } else {
            return RuntimeHealth.UNAVAILABLE;
        }
    }

    public RuntimeHealth evaluate(ModelRuntimeProfile profile) {
        if (profile == null) {
            return RuntimeHealth.UNKNOWN;
        }
        return evaluate(profile.successRate(), profile.timeoutRate(), profile.sampleCount());
    }
}
