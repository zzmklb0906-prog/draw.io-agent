package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;

/**
 * Model Scorer strategy interface.
 *
 * <p>Answers <strong>HOW SUITABLE IS THIS CANDIDATE MODEL?</strong> by computing a normalized
 * score (0 ~ 100) and transparent breakdown against a {@link RoutingRequirement}.</p>
 */
public interface ModelScorer {

    /**
     * Calculates the suitability score for a single model profile within the batch context.
     *
     * @param requirement the request requirements
     * @param model the candidate model profile
     * @param minCostInBatch minimum estimated cost among all candidates in the current batch
     * @param maxCostInBatch maximum estimated cost among all candidates in the current batch
     * @return candidate score record containing total score and detailed breakdown
     */
    CandidateScore score(RoutingRequirement requirement,
                         ModelProfile model,
                         double minCostInBatch,
                         double maxCostInBatch);

    /**
     * Estimates the invocation cost in CNY for the given model profile and requirement.
     *
     * @param requirement the request requirements
     * @param model the candidate model profile
     * @return estimated cost in CNY, or -1.0 if pricing is unavailable
     */
    double estimateCost(RoutingRequirement requirement, ModelProfile model);
}
