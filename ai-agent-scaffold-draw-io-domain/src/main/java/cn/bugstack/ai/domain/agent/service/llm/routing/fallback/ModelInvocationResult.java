package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.FailureType;

import java.util.List;

/**
 * Authoritative outcome of an end-to-end model invocation request (post-fallback).
 *
 * <p><strong>Distinction:</strong>
 * {@link cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelExecutionResult} represents a single raw execution attempt.
 * {@link ModelInvocationResult} represents the final consolidated business outcome after applying {@link FallbackPolicy}.</p>
 */
public record ModelInvocationResult(
        String requestId,
        String selectedModel,
        boolean success,
        String content,
        FailureType failureType,
        String errorMessage,
        int attempts,
        List<InvocationAttempt> attemptsTrace
) {
    public ModelInvocationResult {
        attemptsTrace = attemptsTrace != null ? List.copyOf(attemptsTrace) : List.of();
    }

    public static ModelInvocationResult success(String requestId, String selectedModel, String content, int attempts, List<InvocationAttempt> attemptsTrace) {
        return new ModelInvocationResult(requestId, selectedModel, true, content, FailureType.NONE, null, attempts, attemptsTrace);
    }

    public static ModelInvocationResult failure(String requestId, String selectedModel, FailureType failureType, String errorMessage, int attempts, List<InvocationAttempt> attemptsTrace) {
        return new ModelInvocationResult(requestId, selectedModel, false, null, failureType, errorMessage, attempts, attemptsTrace);
    }
}
