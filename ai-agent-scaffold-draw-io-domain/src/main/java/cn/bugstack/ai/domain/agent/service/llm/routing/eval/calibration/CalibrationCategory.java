package cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration;

/**
 * Categorization of router calibration proposals.
 */
public enum CalibrationCategory {
    REQUIREMENT,
    CATALOG_CAPABILITY,
    SCORING_WEIGHT,
    HARD_CONSTRAINT,
    PROVIDER_RELIABILITY
}
