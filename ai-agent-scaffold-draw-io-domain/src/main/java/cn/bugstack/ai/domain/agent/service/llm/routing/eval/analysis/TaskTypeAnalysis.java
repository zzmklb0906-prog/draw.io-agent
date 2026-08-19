package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

import java.util.Map;

/**
 * Aggregated evaluation metrics sliced by TaskType.
 */
public record TaskTypeAnalysis(
        long totalCount,
        long comparableCount,
        long agreementCount,
        long disagreementCount,
        double agreementRate,
        Map<String, Long> recommendedModelDistribution,
        Map<String, Long> actualModelDistribution,
        Double averageCostDelta
) {
    public TaskTypeAnalysis {
        recommendedModelDistribution = recommendedModelDistribution != null ? Map.copyOf(recommendedModelDistribution) : Map.of();
        actualModelDistribution = actualModelDistribution != null ? Map.copyOf(actualModelDistribution) : Map.of();
    }
}
