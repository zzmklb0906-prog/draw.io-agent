package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintReason;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;

import java.util.List;
import java.util.Map;

/**
 * Comprehensive, immutable offline analysis report generated from a set of {@link cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecord}s.
 */
public record RoutingEvaluationAnalysisReport(
        long totalRecords,
        long comparableRecords,
        long agreementCount,
        long disagreementCount,
        long noRecommendationCount,
        double agreementRate,
        long actualHardRejectedCount,
        double actualHardRejectedRate,
        long catalogLookupFailureCount,
        double catalogLookupFailureRate,
        long pricingUnavailableCount,
        long costComparisonUnavailableCount,
        Map<String, Long> recommendedModelDistribution,
        Map<String, Long> actualModelDistribution,
        Map<TaskType, TaskTypeAnalysis> taskTypeAnalysis,
        Map<String, AgentAnalysis> agentAnalysis,
        Map<ConstraintReason, Long> hardRejectionReasonDistribution,
        ScoreMarginStatistics scoreMarginStatistics,
        CostDeltaStatistics costDeltaStatistics,
        RequirementDimensionStatistics requirementStatistics,
        List<ModelCompetitionPair> modelCompetitionPairs,
        List<RoutingCalibrationRecommendation> recommendations,
        boolean insufficientSample
) {
    public RoutingEvaluationAnalysisReport {
        recommendedModelDistribution = recommendedModelDistribution != null ? Map.copyOf(recommendedModelDistribution) : Map.of();
        actualModelDistribution = actualModelDistribution != null ? Map.copyOf(actualModelDistribution) : Map.of();
        taskTypeAnalysis = taskTypeAnalysis != null ? Map.copyOf(taskTypeAnalysis) : Map.of();
        agentAnalysis = agentAnalysis != null ? Map.copyOf(agentAnalysis) : Map.of();
        hardRejectionReasonDistribution = hardRejectionReasonDistribution != null ? Map.copyOf(hardRejectionReasonDistribution) : Map.of();
        modelCompetitionPairs = modelCompetitionPairs != null ? List.copyOf(modelCompetitionPairs) : List.of();
        recommendations = recommendations != null ? List.copyOf(recommendations) : List.of();
    }
}
