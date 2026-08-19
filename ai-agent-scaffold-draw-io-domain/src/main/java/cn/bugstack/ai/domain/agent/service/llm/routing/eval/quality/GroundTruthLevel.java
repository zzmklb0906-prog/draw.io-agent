package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Reliability classification of the ground truth used for a benchmark case.
 */
public enum GroundTruthLevel {
    DETERMINISTIC,
    RULE_BASED,
    HUMAN_REVIEW,
    UNVERIFIED
}
