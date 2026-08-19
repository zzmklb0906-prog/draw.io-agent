package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe Circuit Breaker protecting external LLM providers from cascading failures.
 *
 * <p><strong>Architectural Responsibility:</strong>
 * Only responsible for ALLOW/DENY decisions regarding provider invocations.
 * Does NOT select models or modify ranking weights.</p>
 */
@Slf4j
@Component
public class ModelCircuitBreaker {

    private final CircuitBreakerProperties properties;
    private final Map<String, CircuitStateContext> stateMap = new ConcurrentHashMap<>();

    public ModelCircuitBreaker(CircuitBreakerProperties properties) {
        this.properties = properties != null ? properties : new CircuitBreakerProperties();
    }

    public ModelCircuitBreaker() {
        this(new CircuitBreakerProperties());
    }

    /**
     * Checks whether an invocation attempt is permitted for the given model.
     */
    public boolean allowRequest(String modelId) {
        if (!properties.isEnabled() || StringUtils.isBlank(modelId)) {
            return true;
        }

        String key = modelId.trim().toLowerCase();
        CircuitStateContext ctx = stateMap.computeIfAbsent(key, k -> new CircuitStateContext());

        synchronized (ctx) {
            long now = System.currentTimeMillis();
            if (ctx.state == CircuitState.OPEN) {
                long elapsed = (now - ctx.lastTransitionTimeMs) / 1000;
                if (elapsed >= properties.getOpenDurationSeconds()) {
                    log.info("Circuit breaker for model [{}] transitioning from OPEN to HALF_OPEN after {}s", modelId, elapsed);
                    ctx.state = CircuitState.HALF_OPEN;
                    ctx.halfOpenPermitsRemaining.set(properties.getHalfOpenRequests());
                    ctx.lastTransitionTimeMs = now;
                } else {
                    return false;
                }
            }

            if (ctx.state == CircuitState.HALF_OPEN) {
                int remaining = ctx.halfOpenPermitsRemaining.getAndDecrement();
                return remaining > 0;
            }

            return ctx.state == CircuitState.CLOSED;
        }
    }

    /**
     * Records a successful execution.
     */
    public void recordSuccess(String modelId) {
        if (StringUtils.isBlank(modelId)) return;
        String key = modelId.trim().toLowerCase();
        CircuitStateContext ctx = stateMap.get(key);
        if (ctx == null) return;

        synchronized (ctx) {
            if (ctx.state == CircuitState.HALF_OPEN || ctx.state == CircuitState.OPEN) {
                log.info("Circuit breaker for model [{}] recovered; transitioning to CLOSED", modelId);
            }
            ctx.state = CircuitState.CLOSED;
            ctx.consecutiveFailures.set(0);
            ctx.lastTransitionTimeMs = System.currentTimeMillis();
        }
    }

    /**
     * Records an execution failure.
     */
    public void recordFailure(String modelId) {
        if (StringUtils.isBlank(modelId)) return;
        String key = modelId.trim().toLowerCase();
        CircuitStateContext ctx = stateMap.computeIfAbsent(key, k -> new CircuitStateContext());

        synchronized (ctx) {
            int failures = ctx.consecutiveFailures.incrementAndGet();
            long now = System.currentTimeMillis();

            if (ctx.state == CircuitState.HALF_OPEN) {
                log.warn("Circuit breaker for model [{}] failed probe request in HALF_OPEN; tripping back to OPEN", modelId);
                ctx.state = CircuitState.OPEN;
                ctx.lastTransitionTimeMs = now;
            } else if (ctx.state == CircuitState.CLOSED && failures >= properties.getFailureThreshold()) {
                log.warn("Circuit breaker for model [{}] tripped to OPEN after {} consecutive failures", modelId, failures);
                ctx.state = CircuitState.OPEN;
                ctx.lastTransitionTimeMs = now;
            }
        }
    }

    /**
     * Inspects the current circuit state for a model.
     */
    public CircuitState getState(String modelId) {
        if (StringUtils.isBlank(modelId)) return CircuitState.CLOSED;
        CircuitStateContext ctx = stateMap.get(modelId.trim().toLowerCase());
        if (ctx == null) return CircuitState.CLOSED;

        synchronized (ctx) {
            long now = System.currentTimeMillis();
            if (ctx.state == CircuitState.OPEN) {
                long elapsed = (now - ctx.lastTransitionTimeMs) / 1000;
                if (elapsed >= properties.getOpenDurationSeconds()) {
                    return CircuitState.HALF_OPEN;
                }
            }
            return ctx.state;
        }
    }

    /**
     * Manually resets circuit state for testing or operator overrides.
     */
    public void reset(String modelId) {
        if (StringUtils.isNotBlank(modelId)) {
            stateMap.remove(modelId.trim().toLowerCase());
        }
    }

    private static class CircuitStateContext {
        CircuitState state = CircuitState.CLOSED;
        AtomicInteger consecutiveFailures = new AtomicInteger(0);
        AtomicInteger halfOpenPermitsRemaining = new AtomicInteger(0);
        long lastTransitionTimeMs = System.currentTimeMillis();
    }
}
