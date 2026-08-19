package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import java.util.List;

/**
 * Detailed breakdown of all scoring components for a model candidate.
 *
 * <p>Provides full explainability for model suitability ranking. Does NOT contain
 * LLM chain-of-thought or subjective reasoning traces.</p>
 */
public record ScoreBreakdown(
        double capabilityFit,
        double reasoningFit,
        double instructionFollowingFit,
        double codingFit,
        double structuredOutputFit,
        double toolCallingFit,
        double contextHeadroomScore,
        double outputHeadroomScore,
        double costScore,
        double uncertaintyPenalty,
        List<String> evidence
) {
    public ScoreBreakdown {
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
    }
}
