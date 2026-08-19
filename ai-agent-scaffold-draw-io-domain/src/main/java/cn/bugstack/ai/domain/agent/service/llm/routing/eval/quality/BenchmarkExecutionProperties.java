package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for offline benchmark quality evaluation.
 *
 * <h3>Known Limitations (Phase 8)</h3>
 * <ul>
 *   <li>Benchmark currently executes <strong>sequentially</strong>.
 *       Parallel execution may be introduced in a future phase.</li>
 *   <li>No automatic retry is performed on failed invocations.
 *       Retry logic may be added in a future phase.</li>
 * </ul>
 *
 * <h3>enabled safety gate</h3>
 * {@link #enabled} defaults to {@code false}.
 * When {@code false}, {@link BenchmarkRunner#run} returns immediately with status
 * {@link BenchmarkRunStatus#DISABLED} and makes <em>zero</em> external model invocations.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.agent.model-routing.benchmark")
public class BenchmarkExecutionProperties {

    /**
     * Whether benchmark execution is enabled.
     * Default {@code false} prevents accidental live API calls during ordinary test runs.
     */
    private boolean enabled = false;

    /**
     * Maximum number of benchmark cases to execute in a single run.
     * Must be >= 1.
     */
    private int maxCases = 30;

    /**
     * Per-invocation HTTP request timeout in seconds.
     * Must be > 0.
     */
    private int requestTimeoutSeconds = 60;

    /**
     * Score tolerance within which two models are deemed quality-equivalent.
     * Must be >= 0.
     */
    private double qualityTieTolerance = 3.0;

    /**
     * Validates that properties hold sensible values.
     *
     * @throws IllegalArgumentException if any value is out of range.
     */
    public void validate() {
        if (maxCases < 1) {
            throw new IllegalArgumentException("benchmark.maxCases must be >= 1, was: " + maxCases);
        }
        if (requestTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("benchmark.requestTimeoutSeconds must be > 0, was: " + requestTimeoutSeconds);
        }
        if (qualityTieTolerance < 0) {
            throw new IllegalArgumentException("benchmark.qualityTieTolerance must be >= 0, was: " + qualityTieTolerance);
        }
    }
}
