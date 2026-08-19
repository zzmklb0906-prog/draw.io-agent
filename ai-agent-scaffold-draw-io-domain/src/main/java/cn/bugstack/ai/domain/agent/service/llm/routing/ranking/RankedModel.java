package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;

import java.util.Map;

/**
 * Ranked Model Candidate.
 *
 * <p>Represents a candidate {@link ModelProfile} that has been scored and ranked,
 * accompanied by an explainable score breakdown and narrative rationale.</p>
 */
public record RankedModel(
        ModelProfile modelProfile,
        double score,
        ScoreBreakdown breakdown,
        String reason
) {
    public record ScoreBreakdown(
            double capabilityFit,
            double contextFit,
            double requirementFit
    ) {
        public Map<String, Double> toMap() {
            return Map.of(
                    "capabilityFit", capabilityFit,
                    "contextFit", contextFit,
                    "requirementFit", requirementFit
            );
        }
    }
}
