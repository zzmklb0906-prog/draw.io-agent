package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

/**
 * Descriptive statistics for estimated cost differences (costDelta = recommended - actual).
 */
public record CostDeltaStatistics(
        long comparableCount,
        Double averageCostDelta,
        Double medianCostDelta,
        long recommendedCheaperCount,
        long recommendedMoreExpensiveCount,
        long sameEstimatedCostCount,
        double cheaperRate,
        double moreExpensiveRate,
        double sameCostRate
) {}
