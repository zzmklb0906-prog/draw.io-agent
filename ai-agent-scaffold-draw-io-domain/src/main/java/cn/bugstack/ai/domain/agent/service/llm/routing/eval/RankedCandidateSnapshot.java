package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

/**
 * Lightweight snapshot of a scored candidate model for evaluation telemetry.
 */
public record RankedCandidateSnapshot(
        String modelId,
        double totalScore,
        double capabilityFit,
        double estimatedCost
) {}
