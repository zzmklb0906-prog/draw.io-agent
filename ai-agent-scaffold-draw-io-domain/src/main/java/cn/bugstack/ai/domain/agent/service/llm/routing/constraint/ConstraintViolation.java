package cn.bugstack.ai.domain.agent.service.llm.routing.constraint;

/**
 * Record representing a single hard constraint violation on a model candidate.
 */
public record ConstraintViolation(
        ConstraintReason reason,
        String field,
        String message
) {}
