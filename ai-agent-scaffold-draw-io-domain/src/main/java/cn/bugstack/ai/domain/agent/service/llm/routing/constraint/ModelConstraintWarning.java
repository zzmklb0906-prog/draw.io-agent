package cn.bugstack.ai.domain.agent.service.llm.routing.constraint;

/**
 * Record representing a non-fatal warning during model constraint evaluation
 * (e.g. vision support is UNKNOWN).
 */
public record ModelConstraintWarning(
        String modelId,
        ConstraintReason reason,
        String message
) {}
