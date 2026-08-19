package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Raw execution outcome of invoking an LLM for a benchmark case.
 *
 * <p><strong>Privacy & Credentials:</strong>
 * Must NEVER persist API keys, Authorization headers, or base URLs.</p>
 */
public record BenchmarkRawResponse(
        boolean success,
        String responseText,
        long latencyMillis,
        String errorType,
        String errorMessage,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens
) {
    public static BenchmarkRawResponse success(String responseText, long latencyMillis, Long promptTokens, Long completionTokens, Long totalTokens) {
        return new BenchmarkRawResponse(true, responseText, latencyMillis, null, null, promptTokens, completionTokens, totalTokens);
    }

    public static BenchmarkRawResponse failure(String errorType, String errorMessage, long latencyMillis) {
        return new BenchmarkRawResponse(false, null, latencyMillis, errorType, errorMessage, null, null, null);
    }
}
