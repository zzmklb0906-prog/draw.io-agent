package cn.bugstack.ai.domain.agent.service.llm.routing.decision;

import java.util.List;

/**
 * Final Model Routing Decision.
 *
 * <p>Represents the authoritative decision outcome produced by {@link RoutingDecisionService}
 * governing active LLM invocation while maintaining policy guardrails.</p>
 */
public record RoutingDecision(
        String selectedModel,
        DecisionSource source,
        List<String> backupCandidates,
        double confidence,
        String reason,
        String dynamicTop1Model,
        String legacyModel,
        boolean isDynamicTakenOver
) {
    public RoutingDecision {
        backupCandidates = backupCandidates != null ? List.copyOf(backupCandidates) : List.of();
    }

    public List<String> candidates() {
        return backupCandidates;
    }
}
