package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

/**
 * Advisory calibration recommendation generated from offline evaluation analysis.
 *
 * <p><strong>Architectural Boundary:</strong>
 * Recommendations are purely diagnostic and informative. They must never be automatically
 * applied to production configurations.</p>
 */
public record RoutingCalibrationRecommendation(
        String code,
        RoutingAnalysisSeverity severity,
        RoutingAnalysisIssueCategory category,
        String dimension,
        String observation,
        String suggestedInvestigation
) {}
