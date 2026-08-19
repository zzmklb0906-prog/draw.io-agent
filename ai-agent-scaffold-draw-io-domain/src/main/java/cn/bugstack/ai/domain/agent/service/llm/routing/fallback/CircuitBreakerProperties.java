package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Model Circuit Breaker.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.model-routing.circuit-breaker")
public class CircuitBreakerProperties {

    private boolean enabled = true;

    /**
     * Number of consecutive failure invocations required to trip the circuit to OPEN.
     */
    private int failureThreshold = 5;

    /**
     * Duration in seconds the circuit stays OPEN before entering HALF_OPEN trial state.
     */
    private long openDurationSeconds = 60L;

    /**
     * Number of allowed trial requests during HALF_OPEN state.
     */
    private int halfOpenRequests = 1;
}
