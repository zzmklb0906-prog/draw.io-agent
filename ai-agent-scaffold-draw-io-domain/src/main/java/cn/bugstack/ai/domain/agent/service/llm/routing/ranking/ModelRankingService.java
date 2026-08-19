package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.CandidateModelSelector;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Model Ranking Service.
 *
 * <p>Orchestrates the dynamic candidate ranking pipeline:
 * {@code RoutingRequirement -> CandidateModelSelector -> Candidate Models -> ModelRankingEngine -> Ranked Models}.</p>
 */
@Service
public class ModelRankingService {

    private final CandidateModelSelector candidateModelSelector;
    private final ModelRankingEngine modelRankingEngine;

    public ModelRankingService(CandidateModelSelector candidateModelSelector,
                               ModelRankingEngine modelRankingEngine) {
        this.candidateModelSelector = candidateModelSelector;
        this.modelRankingEngine = modelRankingEngine;
    }

    /**
     * Selects and ranks candidate models for a given requirement.
     *
     * @param requirement the task capability requirements
     * @return ranked candidate models (descending by score, never null)
     */
    public List<RankedModel> rank(RoutingRequirement requirement) {
        if (candidateModelSelector == null || modelRankingEngine == null) {
            return List.of();
        }
        List<ModelProfile> candidates = candidateModelSelector.select(requirement);
        return modelRankingEngine.rank(requirement, candidates);
    }

    /**
     * Returns the highest-scoring candidate model recommendation, if available.
     */
    public Optional<RankedModel> topRankedModel(RoutingRequirement requirement) {
        List<RankedModel> ranked = rank(requirement);
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
    }
}
