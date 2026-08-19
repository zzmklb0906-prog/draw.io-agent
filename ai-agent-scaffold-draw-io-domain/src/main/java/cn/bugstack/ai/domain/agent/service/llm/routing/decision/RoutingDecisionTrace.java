package cn.bugstack.ai.domain.agent.service.llm.routing.decision;

/**
 * Structural Audit Trace for Model Routing Decisions.
 */
public record RoutingDecisionTrace(
        String requestId,
        RoutingMode mode,
        String legacyModel,
        String dynamicTop1,
        String finalModel,
        DecisionSource source,
        double confidence,
        String reason
) {}
