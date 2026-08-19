package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.FailureType;

import java.util.List;
import java.util.Optional;

/**
 * Policy governing the selection of safe backup models after a primary model failure.
 */
public interface FallbackPolicy {

    /**
     * Selects an eligible backup model from pre-calculated candidate pool.
     *
     * @param failedModel model that just experienced execution failure
     * @param backupCandidates pre-ranked backup candidates from {@link cn.bugstack.ai.domain.agent.service.llm.routing.decision.RoutingDecision}
     * @param failureType classified type of failure
     * @return eligible fallback model if available and healthy, otherwise {@link Optional#empty()}
     */
    Optional<String> selectFallbackModel(
            String failedModel,
            List<String> backupCandidates,
            FailureType failureType
    );
}
