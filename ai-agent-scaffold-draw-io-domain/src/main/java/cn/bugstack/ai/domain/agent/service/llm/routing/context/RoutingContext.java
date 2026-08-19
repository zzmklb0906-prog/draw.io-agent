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
        String explicitModelName,
        boolean hasToolContext
) {
    public RoutingContext(LlmRequest request,
                          String latestUserText,
                          int totalContextChars,
                          long estimatedContextTokens,
                          String agentName,
                          String workflowStage,
                          boolean explicitModel,
                          String explicitModelName) {
        this(request, latestUserText, totalContextChars, estimatedContextTokens,
                agentName, workflowStage, explicitModel, explicitModelName, false);
    }

    /**
     * Alias for {@link #latestUserText()} for domain semantics.
     */
    public String latestUserMessage() {
        return latestUserText;
    }

    /**
     * Input character length of the latest user message.
     */
    public int inputLength() {
        return latestUserText != null ? latestUserText.length() : 0;
    }

    /**
     * Deterministic invocation identifier derived from context signals.
     */
    public String requestId() {
        String base = (agentName != null ? agentName : "default") + ":" + (latestUserText != null ? latestUserText : "");
        return Integer.toHexString(base.hashCode());
    }
}
