package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import java.time.Instant;

/**
 * Model Invocation Execution Result.
 *
 * <p>Represents raw observational outcome captured immediately after LLM execution completes.</p>
 */
public record ModelExecutionResult(
        String requestId,
        String modelId,
        String modelName,
        boolean success,
        long latencyMs,
        Integer inputTokens,
        Integer outputTokens,
        FailureType failureType,
        String errorMessage,
        Instant timestamp
) {
    public ModelExecutionResult {
        if (failureType == null) {
            failureType = success ? FailureType.NONE : FailureType.UNKNOWN;
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public static ModelExecutionResult success(String requestId, String modelId, String modelName, long latencyMs, int inTokens, int outTokens) {
        return new ModelExecutionResult(requestId, modelId, modelName, true, latencyMs, inTokens, outTokens, FailureType.NONE, null, Instant.now());
    }

    public static ModelExecutionResult failure(String requestId, String modelId, String modelName, long latencyMs, FailureType failureType, String errorMessage) {
        return new ModelExecutionResult(requestId, modelId, modelName, false, latencyMs, 0, 0, failureType, errorMessage, Instant.now());
    }
}
