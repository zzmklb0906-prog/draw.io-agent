package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

/**
 * State of a model invocation circuit breaker.
 */
public enum CircuitState {
    /**
     * Normal operational state. All invocations allowed.
     */
    CLOSED,

    /**
     * Tripped state after excessive failures. Invocations rejected immediately.
     */
    OPEN,

    /**
     * Trial state after cooldown timeout. Allows a single probe request to check provider recovery.
     */
    HALF_OPEN
}
