package cn.bugstack.ai.domain.agent.service.llm.routing.context;

import com.google.adk.models.LlmRequest;

/**
 * Routing Context.
 *
 * <p>Encapsulates all extracted runtime signals for a single LLM model invocation,
 * cleanly separating current user intent ({@code latestUserText}) from whole context capacity
 * ({@code totalContextChars}, {@code estimatedContextTokens}), active agent context,
 * and user explicit overrides.</p>
 */
public record RoutingContext(
        LlmRequest request,
        String latestUserText,
        int totalContextChars,
        long estimatedContextTokens,
        String agentName,
        String workflowStage,
        boolean explicitModel,
        String explicitModelName
) {}
