package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.FailureType;

/**
 * Audit trace item of an individual invocation attempt.
 */
public record InvocationAttempt(
        String modelName,
        boolean success,
        long latencyMs,
        FailureType failureType,
        String errorMessage
) {}
