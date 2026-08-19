package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Evaluated quality result for a specific model executing a benchmark case.
 */
public record BenchmarkModelResult(
        String caseId,
        String modelName,
        boolean success,
        String responseText,
        long latencyMillis,
        String errorType,
        Double estimatedCost,
        Long totalTokens,
        ModelQualityScore qualityScore
) {}
