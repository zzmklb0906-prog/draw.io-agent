package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import cn.bugstack.ai.domain.agent.service.llm.routing.decision.RoutingDecision;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.FailureType;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelExecutionResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.RuntimeFeedbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fallback Execution Layer.
 *
 * <p><strong>Architectural Guardrails:</strong>
 * <ul>
 *   <li>Sequential fallback only (never parallel execution of primary and backup).</li>
 *   <li>Hard limit on attempts (governed by {@link FallbackProperties#getMaxAttempts()}).</li>
 *   <li>Integrates with {@link ModelCircuitBreaker} and {@link RuntimeFeedbackService}.</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class FallbackExecutor {

    private final FallbackPolicy fallbackPolicy;
    private final ModelCircuitBreaker circuitBreaker;
    private final RuntimeFeedbackService feedbackService;
    private final FallbackProperties properties;

    public FallbackExecutor(FallbackPolicy fallbackPolicy,
                            ModelCircuitBreaker circuitBreaker,
                            RuntimeFeedbackService feedbackService,
                            FallbackProperties properties) {
        this.fallbackPolicy = fallbackPolicy != null ? fallbackPolicy : new DefaultFallbackPolicy();
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new ModelCircuitBreaker();
        this.feedbackService = feedbackService;
        this.properties = properties != null ? properties : new FallbackProperties();
    }

    /**
     * Executes the invocation with primary model and automatic fallback on failures.
     */
    public ModelInvocationResult execute(RoutingDecision decision, String requestId, ModelInvoker invoker) {
        if (decision == null || invoker == null) {
            return ModelInvocationResult.failure(requestId, "unknown", FailureType.UNKNOWN, "Null decision or invoker", 0, List.of());
        }

        List<InvocationAttempt> attemptsTrace = new ArrayList<>();
        String primaryModel = decision.selectedModel();
        int attemptCount = 0;
        int maxAttempts = Math.max(1, properties.getMaxAttempts());

        // Step 1: Attempt Primary Model
        attemptCount++;
        boolean primaryAllowed = circuitBreaker.allowRequest(primaryModel);
        ModelExecutionResult primaryResult;

        if (!primaryAllowed) {
            log.warn("Primary model [{}] invocation rejected by Circuit Breaker (OPEN)", primaryModel);
            primaryResult = ModelExecutionResult.failure(requestId, primaryModel, primaryModel, 0L, FailureType.PROVIDER_ERROR, "Circuit Breaker is OPEN");
            attemptsTrace.add(new InvocationAttempt(primaryModel, false, 0L, FailureType.PROVIDER_ERROR, "Circuit Breaker OPEN"));
        } else {
            primaryResult = invoker.invoke(primaryModel);
            recordTelemetry(primaryResult);
            attemptsTrace.add(new InvocationAttempt(
                    primaryModel,
                    primaryResult.success(),
                    primaryResult.latencyMs(),
                    primaryResult.failureType(),
                    primaryResult.errorMessage()
            ));
        }

        if (primaryResult.success()) {
            return ModelInvocationResult.success(requestId, primaryModel, "Execution succeeded", attemptCount, attemptsTrace);
        }

        // Step 2: Fallback Evaluation on Failure
        if (attemptCount >= maxAttempts || !properties.isEnabled()) {
            log.warn("Primary invocation failed for model [{}]; maximum attempts reached ({}/{})", primaryModel, attemptCount, maxAttempts);
            return ModelInvocationResult.failure(requestId, primaryModel, primaryResult.failureType(), primaryResult.errorMessage(), attemptCount, attemptsTrace);
        }

        Optional<String> fallbackModelOpt = fallbackPolicy.selectFallbackModel(
                primaryModel,
                decision.backupCandidates(),
                primaryResult.failureType()
        );

        if (fallbackModelOpt.isEmpty()) {
            log.warn("Primary invocation failed for model [{}]; no eligible fallback model available", primaryModel);
            return ModelInvocationResult.failure(requestId, primaryModel, primaryResult.failureType(), primaryResult.errorMessage(), attemptCount, attemptsTrace);
        }

        // Step 3: Attempt Backup Model
        String fallbackModel = fallbackModelOpt.get();
        attemptCount++;
        log.info("Executing fallback attempt {} with model [{}]", attemptCount, fallbackModel);

        boolean backupAllowed = circuitBreaker.allowRequest(fallbackModel);
        ModelExecutionResult backupResult;

        if (!backupAllowed) {
            log.warn("Fallback model [{}] invocation rejected by Circuit Breaker (OPEN)", fallbackModel);
            backupResult = ModelExecutionResult.failure(requestId, fallbackModel, fallbackModel, 0L, FailureType.PROVIDER_ERROR, "Circuit Breaker is OPEN");
            attemptsTrace.add(new InvocationAttempt(fallbackModel, false, 0L, FailureType.PROVIDER_ERROR, "Circuit Breaker OPEN"));
        } else {
            backupResult = invoker.invoke(fallbackModel);
            recordTelemetry(backupResult);
            attemptsTrace.add(new InvocationAttempt(
                    fallbackModel,
                    backupResult.success(),
                    backupResult.latencyMs(),
                    backupResult.failureType(),
                    backupResult.errorMessage()
            ));
        }

        if (backupResult.success()) {
            log.info("Fallback invocation succeeded with backup model [{}]", fallbackModel);
            return ModelInvocationResult.success(requestId, fallbackModel, "Execution succeeded via fallback", attemptCount, attemptsTrace);
        } else {
            log.warn("Fallback invocation failed with backup model [{}]", fallbackModel);
            return ModelInvocationResult.failure(requestId, fallbackModel, backupResult.failureType(), backupResult.errorMessage(), attemptCount, attemptsTrace);
        }
    }

    private void recordTelemetry(ModelExecutionResult result) {
        if (result == null) return;
        String model = result.modelId() != null ? result.modelId() : result.modelName();

        if (result.success()) {
            circuitBreaker.recordSuccess(model);
        } else {
            circuitBreaker.recordFailure(model);
        }

        if (feedbackService != null) {
            feedbackService.recordExecution(result);
        }
    }
}
