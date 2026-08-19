package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable thresholds for runtime feedback collection and health evaluation.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.model-routing.feedback")
public class RuntimeFeedbackProperties {

    private boolean enabled = true;

    /**
     * Minimum observational samples needed before transitioning from UNKNOWN to a deterministic health status.
     */
    private int minSamplesForHealth = 5;

    /**
     * Success rate threshold required to be considered HEALTHY (e.g. 0.95 = 95%).
     */
    private double healthySuccessThreshold = 0.95;

    /**
     * Success rate threshold above which model is DEGRADED; below this is UNAVAILABLE.
     */
    private double degradedSuccessThreshold = 0.80;

    /**
     * Maximum timeout rate tolerated before model is marked DEGRADED or worse.
     */
    private double maxHealthyTimeoutRate = 0.05;
}
