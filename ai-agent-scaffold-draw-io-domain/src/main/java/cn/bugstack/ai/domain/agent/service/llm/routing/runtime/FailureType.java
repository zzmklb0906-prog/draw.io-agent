package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

/**
 * Standard classification of model invocation failures.
 */
public enum FailureType {
    NONE,
    TIMEOUT,
    RATE_LIMIT,
    PROVIDER_ERROR,
    INVALID_RESPONSE,
    UNKNOWN
}
