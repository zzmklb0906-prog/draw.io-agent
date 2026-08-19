package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.decision.DecisionSource;
import cn.bugstack.ai.domain.agent.service.llm.routing.decision.RoutingDecision;
import cn.bugstack.ai.domain.agent.service.llm.routing.fallback.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8 — Fallback Execution + Provider Failure Handling Tests (Case 1 to Case 8)
 */
@DisplayName("Phase 8 Fallback Execution & Provider Failure Handling Tests")
public class FallbackExecutionTest {

    private CircuitBreakerProperties circuitProps;
    private ModelCircuitBreaker circuitBreaker;
    private InMemoryModelRuntimeProfileStore profileStore;
    private FallbackProperties fallbackProps;
    private DefaultFallbackPolicy fallbackPolicy;
    private FallbackExecutor executor;

    @BeforeEach
    void setUp() {
        circuitProps = new CircuitBreakerProperties();
        circuitProps.setFailureThreshold(3);
        circuitProps.setOpenDurationSeconds(2L); // 2 seconds for test cooldown
        circuitBreaker = new ModelCircuitBreaker(circuitProps);

        profileStore = new InMemoryModelRuntimeProfileStore();
        fallbackProps = new FallbackProperties();
        fallbackProps.setMaxAttempts(2);

        fallbackPolicy = new DefaultFallbackPolicy(circuitBreaker, profileStore, fallbackProps);
        executor = new FallbackExecutor(fallbackPolicy, circuitBreaker, null, fallbackProps);
    }

    // =========================================================================
    // Case 1: Primary Model Succeeds
    // =========================================================================
    @Test
    @DisplayName("Case 1: Primary 执行成功 - 验证首选成功时不触发 Fallback，attempts 为 1")
    void case1_primarySuccessNoFallback() {
        RoutingDecision decision = new RoutingDecision(
                "qwen-max", DecisionSource.DYNAMIC_FORCED, List.of("qwen-plus", "qwen-flash"),
                0.90, "Top1 selected", "qwen-max", "qwen-max", true
        );

        ModelInvocationResult result = executor.execute(decision, "req-1", model ->
                ModelExecutionResult.success("req-1", model, model, 800L, 500, 200)
        );

        assertTrue(result.success());
        assertEquals("qwen-max", result.selectedModel());
        assertEquals(1, result.attempts());
        assertEquals(1, result.attemptsTrace().size());
        assertEquals(FailureType.NONE, result.failureType());
    }

    // =========================================================================
    // Case 2: Primary Timeout -> Backup Succeeds
    // =========================================================================
    @Test
    @DisplayName("Case 2: Primary 超时失败 -> Backup 执行成功，验证成功降级切换")
    void case2_primaryTimeoutBackupSuccess() {
        RoutingDecision decision = new RoutingDecision(
                "qwen-max", DecisionSource.DYNAMIC_FORCED, List.of("qwen-plus", "qwen-flash"),
                0.90, "Top1 selected", "qwen-max", "qwen-max", true
        );

        ModelInvocationResult result = executor.execute(decision, "req-2", model -> {
            if ("qwen-max".equals(model)) {
                return ModelExecutionResult.failure("req-2", model, model, 10000L, FailureType.TIMEOUT, "Read timed out");
            }
            return ModelExecutionResult.success("req-2", model, model, 600L, 500, 200);
        });

        assertTrue(result.success());
        assertEquals("qwen-plus", result.selectedModel());
        assertEquals(2, result.attempts());
        assertEquals(2, result.attemptsTrace().size());
        assertEquals(FailureType.TIMEOUT, result.attemptsTrace().get(0).failureType());
        assertEquals(FailureType.NONE, result.attemptsTrace().get(1).failureType());
    }

    // =========================================================================
    // Case 3: Primary Provider Error -> Backup Succeeds
    // =========================================================================
    @Test
    @DisplayName("Case 3: Primary 服务端异常 -> Backup 执行成功")
    void case3_primaryProviderErrorBackupSuccess() {
        RoutingDecision decision = new RoutingDecision(
                "deepseek-r1", DecisionSource.DYNAMIC_FORCED, List.of("qwen-max", "qwen-plus"),
                0.85, "Top1 selected", "deepseek-r1", "deepseek-r1", true
        );

        ModelInvocationResult result = executor.execute(decision, "req-3", model -> {
            if ("deepseek-r1".equals(model)) {
                return ModelExecutionResult.failure("req-3", model, model, 500L, FailureType.PROVIDER_ERROR, "503 Service Unavailable");
            }
            return ModelExecutionResult.success("req-3", model, model, 700L, 400, 200);
        });

        assertTrue(result.success());
        assertEquals("qwen-max", result.selectedModel());
        assertEquals(2, result.attempts());
    }

    // =========================================================================
    // Case 4: Client Fault (User Input Error) Bypasses Fallback
    // =========================================================================
    @Test
    @DisplayName("Case 4: 用户输入错误 (USER_INPUT_ERROR) - 验证明确拒绝 Fallback 重试")
    void case4_userInputErrorBypassesFallback() {
        RoutingDecision decision = new RoutingDecision(
                "qwen-max", DecisionSource.DYNAMIC_FORCED, List.of("qwen-plus", "qwen-flash"),
                0.90, "Top1 selected", "qwen-max", "qwen-max", true
        );

        ModelInvocationResult result = executor.execute(decision, "req-4", model ->
                ModelExecutionResult.failure("req-4", model, model, 100L, FailureType.USER_INPUT_ERROR, "Prompt exceeded max token length")
        );

        assertFalse(result.success());
        assertEquals("qwen-max", result.selectedModel());
        assertEquals(1, result.attempts()); // Stopped at attempt 1
        assertEquals(FailureType.USER_INPUT_ERROR, result.failureType());
    }

    // =========================================================================
    // Case 5: Max Attempts Limit
    // =========================================================================
    @Test
    @DisplayName("Case 5: 最大重试次数限制 - 验证即使有多个候选也绝不超过 maxAttempts (2 次)")
    void case5_maxAttemptsLimit() {
        RoutingDecision decision = new RoutingDecision(
                "model-1", DecisionSource.DYNAMIC_FORCED, List.of("model-2", "model-3", "model-4"),
                0.90, "Top1 selected", "model-1", "model-1", true
        );

        AtomicInteger callCount = new AtomicInteger(0);
        ModelInvocationResult result = executor.execute(decision, "req-5", model -> {
            callCount.incrementAndGet();
            return ModelExecutionResult.failure("req-5", model, model, 200L, FailureType.RATE_LIMIT, "429 Too Many Requests");
        });

        assertFalse(result.success());
        assertEquals(2, callCount.get());
        assertEquals(2, result.attempts());
    }

    // =========================================================================
    // Case 6: Circuit Breaker Trips to OPEN
    // =========================================================================
    @Test
    @DisplayName("Case 6: 连续失败触发熔断 OPEN - 验证 Circuit Breaker 拦截后续请求")
    void case6_circuitBreakerTripsToOpen() {
        String model = "flaky-provider";

        // Record failures up to threshold (3)
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure(model);
        }

        assertEquals(CircuitState.OPEN, circuitBreaker.getState(model));
        assertFalse(circuitBreaker.allowRequest(model));
    }

    // =========================================================================
    // Case 7: Circuit Breaker Transitions to HALF_OPEN
    // =========================================================================
    @Test
    @DisplayName("Case 7: 熔断器冷却后进入 HALF_OPEN - 验证允许探测请求")
    void case7_circuitBreakerHalfOpenProbe() throws InterruptedException {
        String model = "probe-provider";

        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure(model);
        }
        assertEquals(CircuitState.OPEN, circuitBreaker.getState(model));

        // Wait for cooldown (2 seconds)
        Thread.sleep(2100);

        // First probe request allowed
        assertTrue(circuitBreaker.allowRequest(model));
        assertEquals(CircuitState.HALF_OPEN, circuitBreaker.getState(model));

        // Subsequent requests in HALF_OPEN without success are rejected
        assertFalse(circuitBreaker.allowRequest(model));

        // Probe succeeds -> transitions to CLOSED
        circuitBreaker.recordSuccess(model);
        assertEquals(CircuitState.CLOSED, circuitBreaker.getState(model));
        assertTrue(circuitBreaker.allowRequest(model));
    }

    // =========================================================================
    // Case 8: Skip Unavailable Backup Candidates
    // =========================================================================
    @Test
    @DisplayName("Case 8: 跳过不可用 Backup 候选 - 验证自动跳过 UNAVAILABLE 或熔断的备选模型")
    void case8_skipUnavailableBackupCandidates() {
        // model-bad1 is UNAVAILABLE in profileStore
        ModelRuntimeProfile badProfile = new ModelRuntimeProfile("model-bad1", 0.50, 0.30, 8000.0, null, RuntimeHealth.UNAVAILABLE, 50L);
        profileStore.save(badProfile);

        // model-bad2 is OPEN in circuit breaker
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure("model-bad2");
        }

        // model-good is healthy
        ModelRuntimeProfile goodProfile = new ModelRuntimeProfile("model-good", 0.99, 0.01, 1000.0, null, RuntimeHealth.HEALTHY, 50L);
        profileStore.save(goodProfile);

        Optional<String> fallbackOpt = fallbackPolicy.selectFallbackModel(
                "primary-model",
                List.of("model-bad1", "model-bad2", "model-good"),
                FailureType.PROVIDER_ERROR
        );

        assertTrue(fallbackOpt.isPresent());
        assertEquals("model-good", fallbackOpt.get());
    }
}
