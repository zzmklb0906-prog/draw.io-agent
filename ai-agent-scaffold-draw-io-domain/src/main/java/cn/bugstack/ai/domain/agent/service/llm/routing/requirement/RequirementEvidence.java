package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import java.util.List;

/**
 * Explanatory Evidence Record for a {@link RoutingRequirement}.
 *
 * <p><strong>Notice:</strong> Evidence records deterministic rule matching and policy adjustments.
 * It is NOT a chain-of-thought or LLM reasoning trace.</p>
 */
public record RequirementEvidence(
        List<String> taskSignals,
        List<String> matchedPatterns,
        List<String> adjustments,
        long estimatedContextTokens,
        long expectedOutputTokens
) {
    public static RequirementEvidence empty() {
        return new RequirementEvidence(List.of(), List.of(), List.of(), 0L, 0L);
    }
}
