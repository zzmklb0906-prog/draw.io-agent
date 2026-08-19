package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import java.time.Instant;

/**
 * Normalized Runtime Feedback Event.
 *
 * <p>Represents an immutable telemetry event generated from a {@link ModelExecutionResult}.</p>
 */
public record RuntimeFeedback(
        String requestId,
        String modelId,
        boolean success,
        long latencyMs,
        FailureType failureType,
        String failureReason,
        Integer inputTokens,
        Integer outputTokens,
        Instant timestamp
) {
    public RuntimeFeedback {
        if (failureType == null) {
            failureType = success ? FailureType.NONE : FailureType.UNKNOWN;
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
