package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.FailureType;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelRuntimeProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelRuntimeProfileStore;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.RuntimeHealth;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link FallbackPolicy}.
 *
 * <p><strong>Safety Constraints:</strong>
 * <ul>
 *   <li>Rejects fallback for {@link FailureType#USER_INPUT_ERROR}.</li>
 *   <li>Filters out models with {@link CircuitState#OPEN}.</li>
 *   <li>Filters out models with {@link RuntimeHealth#UNAVAILABLE}.</li>
 *   <li>Does not re-invoke ranking engine; strictly consumes pre-computed {@code backupCandidates}.</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class DefaultFallbackPolicy implements FallbackPolicy {

    private final ModelCircuitBreaker circuitBreaker;
    private final ModelRuntimeProfileStore profileStore;
    private final FallbackProperties properties;

    public DefaultFallbackPolicy(ModelCircuitBreaker circuitBreaker,
                                 ModelRuntimeProfileStore profileStore,
                                 FallbackProperties properties) {
        this.circuitBreaker = circuitBreaker;
        this.profileStore = profileStore;
        this.properties = properties != null ? properties : new FallbackProperties();
    }

    public DefaultFallbackPolicy(ModelCircuitBreaker circuitBreaker, ModelRuntimeProfileStore profileStore) {
        this(circuitBreaker, profileStore, new FallbackProperties());
    }

    public DefaultFallbackPolicy() {
        this(null, null, new FallbackProperties());
    }

    @Override
    public Optional<String> selectFallbackModel(String failedModel,
                                               List<String> backupCandidates,
                                               FailureType failureType) {
        if (!properties.isEnabled()) {
            log.info("Fallback execution is disabled by configuration");
            return Optional.empty();
        }

        // Rule 1: User input errors are client faults; changing model will not succeed
        if (failureType == FailureType.USER_INPUT_ERROR) {
            log.info("Bypassing fallback for client fault error: {}", failureType);
            return Optional.empty();
        }

        if (backupCandidates == null || backupCandidates.isEmpty()) {
            log.info("No backup candidates provided in routing decision");
            return Optional.empty();
        }

        for (String candidate : backupCandidates) {
            if (StringUtils.isBlank(candidate)) continue;

            // Cannot fallback to the same failed model
            if (candidate.equalsIgnoreCase(failedModel)) {
                continue;
            }

            // Circuit Breaker check
            if (circuitBreaker != null && !circuitBreaker.allowRequest(candidate)) {
                log.warn("Candidate fallback model [{}] rejected: Circuit Breaker is OPEN", candidate);
                continue;
            }

            // Runtime Health check
            if (profileStore != null) {
                Optional<ModelRuntimeProfile> profileOpt = profileStore.find(candidate);
                if (profileOpt.isPresent() && profileOpt.get().health() == RuntimeHealth.UNAVAILABLE) {
                    log.warn("Candidate fallback model [{}] rejected: Runtime Health is UNAVAILABLE", candidate);
                    continue;
                }
            }

            log.info("Selected fallback model [{}] after [{}] failed with [{}]", candidate, failedModel, failureType);
            return Optional.of(candidate);
        }

        log.warn("All backup candidates were rejected due to health/circuit status or were exhausted");
        return Optional.empty();
    }
}
