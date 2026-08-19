package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelRuntimeProfile;

import java.util.List;
import java.util.Map;

/**
 * Model Ranking Engine interface.
 *
 * <p>Scores, explains, and ranks candidate {@link ModelProfile}s against task requirements and runtime observations.</p>
 */
public interface ModelRankingEngine {

    /**
     * Primary rank entry point incorporating dynamic runtime profiles.
     */
    List<RankedModel> rank(RoutingRequirement requirement,
                           List<ModelProfile> candidates,
                           Map<String, ModelRuntimeProfile> runtimeProfiles);

    /**
     * Backward-compatible overload without explicit runtime profiles.
     */
    default List<RankedModel> rank(RoutingRequirement requirement, List<ModelProfile> candidates) {
        return rank(requirement, candidates, Map.of());
    }
}
