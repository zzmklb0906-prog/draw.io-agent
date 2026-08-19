package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Qualitative comparison of a router's selection against the benchmark's optimal models.
 */
public enum RoutingQualityClassification {
    OPTIMAL,
    QUALITY_EQUIVALENT,
    POTENTIAL_OVER_ROUTING,
    UNDER_ROUTING,
    NO_VALID_COMPARISON
}
