package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default implementation of {@link ModelRankingEngine}.
 *
 * <p><strong>Scoring Model:</strong>
 * {@code Final Score = (Capability Match * 0.5) + (Context Fit * 0.3) + (Requirement Match * 0.2)}
 * </p>
 */
@Component
public class DefaultModelRankingEngine implements ModelRankingEngine {

    @Override
    public List<RankedModel> rank(RoutingRequirement requirement, List<ModelProfile> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RankedModel> ranked = new ArrayList<>();
        for (ModelProfile candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            double capabilityFit = calculateCapabilityFit(requirement, candidate);
            double contextFit = calculateContextFit(requirement, candidate);
            double requirementFit = calculateRequirementFit(requirement, candidate);

            double finalScore = Math.round(((capabilityFit * 0.5) + (contextFit * 0.3) + (requirementFit * 0.2)) * 100.0) / 100.0;

            RankedModel.ScoreBreakdown breakdown = new RankedModel.ScoreBreakdown(
                    Math.round(capabilityFit * 100.0) / 100.0,
                    Math.round(contextFit * 100.0) / 100.0,
                    Math.round(requirementFit * 100.0) / 100.0
            );

            String reason = String.format(
                    "Model: %s | Final Score: %.2f | Reason: Capability Match: %.2f, Context Fit: %.2f, Requirement Match: %.2f",
                    candidate.id(), finalScore, breakdown.capabilityFit(), breakdown.contextFit(), breakdown.requirementFit()
            );

            ranked.add(new RankedModel(candidate, finalScore, breakdown, reason));
        }

        // Deterministic sort: descending by score, ascending by candidate id
        ranked.sort(Comparator.comparingDouble(RankedModel::score).reversed()
                .thenComparing(r -> r.modelProfile().id()));

        return List.copyOf(ranked);
    }

    private double calculateCapabilityFit(RoutingRequirement requirement, ModelProfile model) {
        if (model.capabilities() == null) {
            return 0.5;
        }

        double base = (model.capabilities().reasoning() + model.capabilities().instructionFollowing()) / 200.0;

        if (requirement == null) {
            return base;
        }

        double featureBonus = 0.0;
        int featureCount = 0;

        if (requirement.needToolCalling()) {
            featureCount++;
            if (model.supportsToolCalling()) featureBonus += 1.0;
        }
        if (requirement.needVision()) {
            featureCount++;
            if (model.supportsVision()) featureBonus += 1.0;
        }
        if (requirement.needStructuredOutput()) {
            featureCount++;
            if (model.supportsStructuredOutput()) featureBonus += 1.0;
        }

        if (featureCount > 0) {
            return (base * 0.4) + ((featureBonus / featureCount) * 0.6);
        }
        return base;
    }

    private double calculateContextFit(RoutingRequirement requirement, ModelProfile model) {
        if (requirement == null || requirement.minContextWindowTokens() <= 0) {
            return 1.0;
        }

        long required = requirement.minContextWindowTokens();
        long available = model.contextWindow();

        if (available >= required) {
            // Larger headroom yields a higher context score (normalized up to 2x capacity)
            double ratio = (double) available / Math.max(1L, required * 2);
            return Math.min(1.0, 0.7 + (0.3 * Math.min(1.0, ratio)));
        } else {
            return Math.max(0.1, (double) available / Math.max(1L, required) * 0.5);
        }
    }

    private double calculateRequirementFit(RoutingRequirement requirement, ModelProfile model) {
        if (model.capabilities() == null || requirement == null) {
            return 0.6;
        }

        int complexity = requirement.estimatedComplexity();
        int reasoning = model.capabilities().reasoning();

        if (complexity >= 3) {
            return reasoning >= 80 ? 0.95 : (reasoning / 100.0);
        } else if (complexity <= 1) {
            return 0.85;
        } else {
            return (reasoning >= 60 && reasoning <= 90) ? 0.90 : 0.75;
        }
    }
}
