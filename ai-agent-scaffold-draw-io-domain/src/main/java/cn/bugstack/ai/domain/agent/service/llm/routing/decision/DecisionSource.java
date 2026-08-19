package cn.bugstack.ai.domain.agent.service.llm.routing.decision;

/**
 * Origin source of the final routing decision.
 */
public enum DecisionSource {
    LEGACY,
    DYNAMIC_SHADOW,
    DYNAMIC_CANARY,
    DYNAMIC_FORCED,
    EXPLICIT_OVERRIDE
}
