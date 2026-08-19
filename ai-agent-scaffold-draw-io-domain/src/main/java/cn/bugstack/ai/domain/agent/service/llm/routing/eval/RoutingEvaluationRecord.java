package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintReason;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured Evaluation Record for Shadow Routing telemetry and offline calibration.
 *
 * <p><strong>Privacy Boundary:</strong>
 * This record strictly avoids persisting raw user text, full conversation turns, images,
 * API keys, headers, or base URLs.</p>
 */
public record RoutingEvaluationRecord(
        String invocationId,
        String agentName,
        TaskType taskType,
        String actualModel,
        String recommendedModel,
        Boolean matched,
        Double recommendedScore,
        SelectionSource actualSource,
        int acceptedCandidateCount,
        int rejectedCandidateCount,
        Map<String, List<ConstraintReason>> rejectedReasons,
        Double top1Score,
        Double top2Score,
        Double scoreMargin,
        Double actualModelScore,
        Double estimatedRecommendedCost,
        Double estimatedActualCost,
        Double costDelta,
        RequirementSnapshot requirementSnapshot,
        List<RankedCandidateSnapshot> topCandidates,
        Set<RoutingEvaluationFlag> flags,
        Instant timestamp
) {
    public RoutingEvaluationRecord {
        rejectedReasons = rejectedReasons != null ? Map.copyOf(rejectedReasons) : Map.of();
        topCandidates = topCandidates != null ? List.copyOf(topCandidates) : List.of();
        flags = flags != null ? Set.copyOf(flags) : Set.of();
        timestamp = timestamp != null ? timestamp : Instant.now();
    }
}
