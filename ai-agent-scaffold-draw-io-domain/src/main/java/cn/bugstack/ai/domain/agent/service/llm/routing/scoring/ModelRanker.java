package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;

import java.util.List;

/**
 * Model Ranker strategy interface.
 *
 * <p>Scores, sorts, and breaks ties among accepted model candidates against a {@link RoutingRequirement}.</p>
 */
public interface ModelRanker {

    /**
     * Ranks the given list of accepted candidate models.
     *
     * @param requirement the request requirements
     * @param candidates accepted candidate model profiles
     * @return deterministic {@link RankingResult}
     */
    RankingResult rank(RoutingRequirement requirement, List<ModelProfile> candidates);
}
