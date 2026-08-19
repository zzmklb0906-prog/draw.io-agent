package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.CandidateModelSelector;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelRuntimeProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelRuntimeProfileStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Model Ranking Service.
 *
 * <p>Orchestrates the dynamic candidate ranking pipeline:
 * {@code RoutingRequirement -> CandidateModelSelector -> Candidate Models -> Runtime Profiles Store -> ModelRankingEngine -> Ranked Models}.</p>
 */
@Service
public class ModelRankingService {

    private final CandidateModelSelector candidateModelSelector;
    private final ModelRankingEngine modelRankingEngine;
    private final ModelRuntimeProfileStore runtimeStore;

    public ModelRankingService(CandidateModelSelector candidateModelSelector,
                               ModelRankingEngine modelRankingEngine,
                               ModelRuntimeProfileStore runtimeStore) {
        this.candidateModelSelector = candidateModelSelector;
        this.modelRankingEngine = modelRankingEngine;
        this.runtimeStore = runtimeStore;
    }

    public ModelRankingService(CandidateModelSelector candidateModelSelector,
                               ModelRankingEngine modelRankingEngine) {
        this(candidateModelSelector, modelRankingEngine, null);
    }

    /**
     * Selects and ranks candidate models for a given requirement, incorporating dynamic runtime profiles.
     *
     * @param requirement the task capability requirements
     * @return ranked candidate models (descending by score, never null)
     */
    public List<RankedModel> rank(RoutingRequirement requirement) {
        if (candidateModelSelector == null || modelRankingEngine == null) {
            return List.of();
        }
        List<ModelProfile> candidates = candidateModelSelector.select(requirement);

        Map<String, ModelRuntimeProfile> runtimeMap = new HashMap<>();
        if (runtimeStore != null) {
            for (ModelProfile candidate : candidates) {
                if (candidate != null) {
                    runtimeStore.find(candidate.id()).ifPresent(p -> runtimeMap.put(candidate.id().toLowerCase(), p));
                }
            }
        }

        return modelRankingEngine.rank(requirement, candidates, runtimeMap);
    }

    /**
     * Returns the highest-scoring candidate model recommendation, if available.
     */
    public Optional<RankedModel> topRankedModel(RoutingRequirement requirement) {
        List<RankedModel> ranked = rank(requirement);
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
    }
}
