package cn.bugstack.ai.domain.agent.service.agent.routing;

/**
 * Ranked Agent with explainable multi-objective score breakdown.
 */
public record RankedAgent(
        AgentProfile profile,
        double score,
        double capabilityMatchScore,
        double taskMatchScore,
        double toolMatchScore,
        String reason
) {}
