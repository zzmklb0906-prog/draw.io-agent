package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Summary metrics of a router's selection quality across benchmark cases.
 */
public record RouterQualitySummary(
        long evaluatedCases,
        Double averageRegret,
        long optimalCount,
        long qualityEquivalentCount,
        long potentialOverRoutingCount,
        long underRoutingCount,
        double optimalRate,
        double qualityEquivalentRate,
        double overRoutingRate,
        double underRoutingRate
) {}
