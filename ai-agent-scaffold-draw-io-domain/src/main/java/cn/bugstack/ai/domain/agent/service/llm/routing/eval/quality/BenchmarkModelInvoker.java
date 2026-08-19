package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Adapter interface for invoking models during offline benchmarking.
 */
public interface BenchmarkModelInvoker {

    /**
     * Executes a benchmark invocation for the given model identifier and case.
     */
    BenchmarkRawResponse invoke(String modelName, BenchmarkCase benchmarkCase);
}
