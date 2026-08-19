package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;

import java.util.Map;

/**
 * Ranked Model Candidate.
 *
 * <p>Represents a candidate {@link ModelProfile} scored against multi-objective utility criteria
 * (capability, requirement, reliability, latency, cost, context) with an explainable breakdown.</p>
 */
public record RankedModel(
        ModelProfile modelProfile,
        double score,
        ScoreBreakdown breakdown,
        String reason,
        boolean runtimeEvidenceAvailable,
        Double estimatedCost
) {
    public record ScoreBreakdown(
            double capabilityFit,
            double requirementFit,
            double reliabilityFit,
            double latencyFit,
            double costFit,
            double contextFit
    ) {
        public Map<String, Double> toMap() {
            return Map.of(
                    "capabilityFit", capabilityFit,
                    "requirementFit", requirementFit,
                    "reliabilityFit", reliabilityFit,
                    "latencyFit", latencyFit,
                    "costFit", costFit,
                    "contextFit", contextFit
            );
        }
    }
}
