package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;

import java.util.Map;

/**
 * Aggregated summary of Shadow Routing Evaluation metrics.
 */
public record RoutingEvaluationSummary(
        long totalInvocations,
        long comparableInvocations,
        long agreementCount,
        long disagreementCount,
        long noRecommendationCount,
        double agreementRate,
        Map<TaskType, Long> byTaskType,
        Map<String, Long> byAgent,
        Map<String, Long> byRecommendedModel,
        Map<String, Long> byActualModel
) {}
