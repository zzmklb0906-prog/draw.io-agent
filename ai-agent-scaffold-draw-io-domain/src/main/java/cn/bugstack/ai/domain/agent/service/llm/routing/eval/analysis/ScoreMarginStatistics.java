package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

/**
 * Descriptive statistics for score margin between top-1 and top-2 candidates.
 */
public record ScoreMarginStatistics(
        long sampleCount,
        Double average,
        Double median,
        Double p25,
        Double p75,
        Double p90,
        long lowMarginCount,
        double lowMarginRate
) {}
