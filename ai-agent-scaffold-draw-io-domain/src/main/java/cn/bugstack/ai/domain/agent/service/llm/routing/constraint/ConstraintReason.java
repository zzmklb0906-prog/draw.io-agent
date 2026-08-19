package cn.bugstack.ai.domain.agent.service.llm.routing.constraint;

/**
 * Hard Constraint Violation and Warning Reasons.
 */
public enum ConstraintReason {
    MODEL_DISABLED,
    VISION_UNSUPPORTED,
    VISION_SUPPORT_UNKNOWN,
    CONTEXT_WINDOW_TOO_SMALL,
    MAX_OUTPUT_TOO_SMALL,
    INVALID_MODEL_METADATA,
    INVALID_REQUIREMENT
}
