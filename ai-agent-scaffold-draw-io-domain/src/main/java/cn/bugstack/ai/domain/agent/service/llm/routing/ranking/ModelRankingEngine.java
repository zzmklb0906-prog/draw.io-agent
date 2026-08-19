package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;

import java.util.List;

/**
 * Model Ranking Engine interface.
 *
 * <p>Scores, explains, and ranks candidate {@link ModelProfile}s against a given {@link RoutingRequirement}.</p>
 */
public interface ModelRankingEngine {

    /**
     * Ranks candidate models against task requirements.
     *
     * @param requirement the task capability requirements
     * @param candidates candidate model profiles to rank
     * @return sorted list of ranked models in descending order of score (never null)
     */
    List<RankedModel> rank(RoutingRequirement requirement, List<ModelProfile> candidates);
}
