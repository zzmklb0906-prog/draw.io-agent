package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Execution status of a {@link BenchmarkRunner#run} invocation.
 *
 * <p>Distinguishes between successful completion, early-exit conditions, and disabled state
 * so callers can precisely interpret an empty or zero-count {@link BenchmarkReport}.</p>
 */
public enum BenchmarkRunStatus {

    /**
     * Benchmark executed and produced a valid report.
     */
    COMPLETED,

    /**
     * Benchmark execution was skipped because {@link BenchmarkExecutionProperties#isEnabled()} == false.
     * <p>No external model invocations were made.</p>
     */
    DISABLED,

    /**
     * Benchmark was enabled but the supplied dataset contained no cases.
     */
    NO_DATA,

    /**
     * Benchmark was enabled and had cases, but no models were available to evaluate.
     */
    NO_MODELS
}
