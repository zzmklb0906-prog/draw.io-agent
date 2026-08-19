package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelFilterResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for orchestrating dynamic model scoring and ranking over Phase 4 accepted candidates.
 */
@Service
public class DynamicModelRankingService {

    private final ModelRanker modelRanker;

    public DynamicModelRankingService(ModelRanker modelRanker) {
        this.modelRanker = modelRanker;
    }

    /**
     * Ranks only the accepted candidate models produced by Phase 4 Hard Constraint Filter.
     *
     * @param requirement the request requirements
     * @param filterResult the result from Phase 4 Hard Constraint Filter
     * @return ranking result containing scored and sorted candidates (empty if no accepted models)
     */
    public RankingResult rank(RoutingRequirement requirement, ModelFilterResult filterResult) {
        if (filterResult == null || !filterResult.hasAcceptedModels()) {
            return RankingResult.empty();
        }
        return rank(requirement, filterResult.accepted());
    }

    /**
     * Ranks an explicit list of accepted candidate models.
     *
     * @param requirement the request requirements
     * @param acceptedCandidates list of accepted candidate model profiles
     * @return ranking result
     */
    public RankingResult rank(RoutingRequirement requirement, List<ModelProfile> acceptedCandidates) {
        if (acceptedCandidates == null || acceptedCandidates.isEmpty()) {
            return RankingResult.empty();
        }
        return modelRanker.rank(requirement, acceptedCandidates);
    }
}
