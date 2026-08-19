package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

import java.util.Map;

/**
 * Aggregated evaluation metrics sliced by Agent Name.
 */
public record AgentAnalysis(
        long totalCount,
        long comparableCount,
        long agreementCount,
        long disagreementCount,
        double agreementRate,
        Map<String, Long> recommendedModelDistribution,
        Map<String, Long> actualModelDistribution
) {
    public AgentAnalysis {
        recommendedModelDistribution = recommendedModelDistribution != null ? Map.copyOf(recommendedModelDistribution) : Map.of();
        actualModelDistribution = actualModelDistribution != null ? Map.copyOf(actualModelDistribution) : Map.of();
    }
}
