package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

/**
 * Diagnostic and behavioral flags for routing evaluation.
 *
 * <p><strong>Terminology Notice:</strong>
 * {@link #MATCHED} and {@link #UNMATCHED} denote agreement or disagreement between
 * Dynamic Router Recommendation and Legacy Router selection. They do NOT denote correctness or accuracy.</p>
 */
public enum RoutingEvaluationFlag {
    MATCHED,
    UNMATCHED,
    NO_DYNAMIC_RECOMMENDATION,
    NO_ELIGIBLE_CANDIDATE,
    ACTUAL_MODEL_NOT_IN_CATALOG,
    ACTUAL_MODEL_HARD_REJECTED,
    LOW_SCORE_MARGIN,
    VISION_REQUIRED,
    UNKNOWN_FEATURE_PRESENT,
    PRICING_UNAVAILABLE
}
