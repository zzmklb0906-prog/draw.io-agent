package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Aggregated benchmark quality summary for a specific model across all executed cases.
 */
public record ModelOverallQuality(
        long executions,
        long successCount,
        long passCount,
        double passRate,
        Double averageQuality,
        Double averageLatencyMillis,
        Double averageCost
) {}
