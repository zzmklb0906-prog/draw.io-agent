package cn.bugstack.ai.domain.agent.service.llm.routing.decision;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Routing Decision and Controlled Takeover.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.model-routing.decision")
public class RoutingDecisionProperties {

    /**
     * Routing operational mode (LEGACY, SHADOW, CANARY, DYNAMIC). Default is SHADOW.
     */
    private RoutingMode mode = RoutingMode.SHADOW;

    /**
     * Percentage of requests to route dynamically in CANARY mode (0 to 100). Default is 0.
     */
    private int canaryPercentage = 0;

    /**
     * Minimum score gap between Top1 and Top2 for high confidence. Default is 0.05.
     */
    private double minConfidenceThreshold = 0.05;

    /**
     * Whether explicit model requested by caller takes absolute precedence. Default is true.
     */
    private boolean allowExplicitOverride = true;

    public void validate() {
        if (canaryPercentage < 0 || canaryPercentage > 100) {
            throw new IllegalArgumentException("canaryPercentage must be between 0 and 100");
        }
    }
}
