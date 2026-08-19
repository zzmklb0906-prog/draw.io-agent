package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for offline benchmark quality evaluation.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.agent.model-routing.benchmark")
public class BenchmarkExecutionProperties {

    /**
     * Whether benchmark execution is enabled. Default is false to prevent accidental live runs.
     */
    private boolean enabled = false;

    /**
     * Maximum number of cases to execute in a single benchmark run.
     */
    private int maxCases = 30;

    /**
     * Maximum number of concurrent model invocations.
     */
    private int maxConcurrency = 2;

    /**
     * Per-invocation HTTP request timeout in seconds.
     */
    private int requestTimeoutSeconds = 60;

    /**
     * Maximum retry attempts for failed model invocations.
     */
    private int maxRetries = 0;

    /**
     * Score tolerance within which two models are deemed quality-equivalent.
     */
    private double qualityTieTolerance = 3.0;
}
