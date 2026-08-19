package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable aggregated offline quality benchmark evaluation report.
 *
 * <p>The {@link #runStatus} field explicitly encodes why a run may have produced zero results,
 * preventing callers from misinterpreting {@link BenchmarkRunStatus#DISABLED} as {@link BenchmarkRunStatus#NO_DATA}.</p>
 */
public record BenchmarkReport(
        String datasetId,
        String datasetVersion,
        BenchmarkRunStatus runStatus,
        String skippedReason,
        long totalCases,
        long executedCases,
        long modelExecutions,
        double executionSuccessRate,
        Map<String, ModelOverallQuality> perModelQuality,
        Map<TaskType, Map<String, Double>> taskTypeModelMatrix,
        RouterQualitySummary dynamicSummary,
        RouterQualitySummary legacySummary,
        long dynamicBetterCount,
        long legacyBetterCount,
        long equivalentCount,
        List<RoutingQualityEvaluation> caseEvaluations,
        List<String> advisoryRecommendations,
        Instant timestamp
) {
    public BenchmarkReport {
        if (runStatus == null) runStatus = BenchmarkRunStatus.COMPLETED;
        perModelQuality = perModelQuality != null ? Map.copyOf(perModelQuality) : Map.of();
        taskTypeModelMatrix = taskTypeModelMatrix != null ? Map.copyOf(taskTypeModelMatrix) : Map.of();
        caseEvaluations = caseEvaluations != null ? List.copyOf(caseEvaluations) : List.of();
        advisoryRecommendations = advisoryRecommendations != null ? List.copyOf(advisoryRecommendations) : List.of();
    }
}
