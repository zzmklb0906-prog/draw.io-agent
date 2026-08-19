package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Strategy interface for deterministic, rule-based response quality evaluation.
 */
public interface ResponseQualityEvaluator {

    /**
     * Checks if this evaluator supports the given benchmark case.
     */
    boolean supports(BenchmarkCase benchmarkCase);

    /**
     * Evaluates a raw model response against the benchmark case expectations.
     */
    ModelQualityScore evaluate(BenchmarkCase benchmarkCase, BenchmarkRawResponse rawResponse);
}
