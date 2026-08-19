package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Aggregated benchmark quality summary for a specific model across all executed cases.
 *
 * <h3>Reliability vs Quality denominator separation</h3>
 * <ul>
 *   <li>{@link #successRate} = successCount / executions — measures execution reliability.</li>
 *   <li>{@link #qualityPassRate} = passCount / successCount — measures response quality among
 *       executions that actually produced a response.  Failed executions are excluded from this
 *       denominator to avoid conflating provider outages with model quality.</li>
 *   <li>{@link #averageQuality} is similarly computed only over successful executions.</li>
 * </ul>
 */
public record ModelOverallQuality(
        /** Total number of invocations attempted (success + failure). */
        long executions,
        /** Number of invocations that produced a valid response (no exception / timeout). */
        long successCount,
        /**
         * Execution reliability rate = successCount / executions.
         * 0.0 if executions == 0.
         */
        double successRate,
        /**
         * Number of successful executions whose quality score exceeded the pass threshold (>= 70.0).
         * Failures are excluded from this count.
         */
        long passCount,
        /**
         * Quality pass rate among successful executions = passCount / successCount.
         * {@code null} when successCount == 0 (avoids division-by-zero / NaN).
         */
        Double qualityPassRate,
        /**
         * Average quality score computed only over successful executions with non-null scores.
         * {@code null} when no successful quality evaluations exist.
         */
        Double averageQuality,
        /** Average latency in milliseconds over successful executions; {@code null} if none succeeded. */
        Double averageLatencyMillis,
        /** Average estimated cost over executions that had cost data; {@code null} if unavailable. */
        Double averageCost
) {}
