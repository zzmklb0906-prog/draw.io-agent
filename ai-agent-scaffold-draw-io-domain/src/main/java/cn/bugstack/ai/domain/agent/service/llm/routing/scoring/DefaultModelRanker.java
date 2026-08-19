package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default deterministic implementation of {@link ModelRanker}.
 *
 * <p><strong>Deterministic Multi-tier Tie-break:</strong>
 * <ol>
 *   <li>{@code totalScore} descending</li>
 *   <li>{@code capabilityFit} descending</li>
 *   <li>{@code estimatedCost} ascending (cheaper is preferred)</li>
 *   <li>{@code model.id} lexical ascending (absolute deterministic tie-break, independent of catalog input order)</li>
 * </ol>
 * </p>
 */
@Component
public class DefaultModelRanker implements ModelRanker {

    private final ModelScorer modelScorer;

    public DefaultModelRanker(ModelScorer modelScorer) {
        this.modelScorer = modelScorer;
    }

    @Override
    public RankingResult rank(RoutingRequirement requirement, List<ModelProfile> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return RankingResult.empty();
        }

        // 1. Calculate cost bounds across the candidate batch
        double minCost = Double.MAX_VALUE;
        double maxCost = 0.0;
        boolean hasValidCost = false;

        for (ModelProfile candidate : candidates) {
            if (candidate == null) continue;
            double cost = modelScorer.estimateCost(requirement, candidate);
            if (cost >= 0) {
                hasValidCost = true;
                if (cost < minCost) minCost = cost;
                if (cost > maxCost) maxCost = cost;
            }
        }
        if (!hasValidCost) {
            minCost = 0.0;
            maxCost = 0.0;
        }

        // 2. Score all candidates
        List<CandidateScore> scores = new ArrayList<>();
        for (ModelProfile candidate : candidates) {
            if (candidate == null) continue;
            CandidateScore candidateScore = modelScorer.score(requirement, candidate, minCost, maxCost);
            scores.add(candidateScore);
        }

        // 3. Deterministic Sorting with multi-tier tie-breaking
        scores.sort(Comparator
                // 1. Total Score (descending)
                .<CandidateScore>comparingDouble(CandidateScore::totalScore).reversed()
                // 2. Capability Fit (descending)
                .thenComparing(Comparator.comparingDouble((CandidateScore cs) -> cs.breakdown().capabilityFit()).reversed())
                // 3. Estimated Cost (ascending; missing pricing treated as POSITIVE_INFINITY so it never beats known positive costs)
                .thenComparingDouble(DefaultModelRanker::sortableCost)
                // 4. Model ID Lexical (ascending)
                .thenComparing(cs -> cs.model().id(), String.CASE_INSENSITIVE_ORDER)
        );

        return new RankingResult(scores);
    }

    private static double sortableCost(CandidateScore score) {
        return score.estimatedCost() >= 0.0 ? score.estimatedCost() : Double.POSITIVE_INFINITY;
    }
}
