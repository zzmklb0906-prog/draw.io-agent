package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

/**
 * Model Runtime Health Status.
 *
 * <p><strong>Notice:</strong> UNKNOWN indicates lack of runtime observations,
 * which does NOT mean the model is unavailable.</p>
 */
public enum RuntimeHealth {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN
}
