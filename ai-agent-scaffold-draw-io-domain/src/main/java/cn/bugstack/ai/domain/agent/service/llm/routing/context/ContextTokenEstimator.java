package cn.bugstack.ai.domain.agent.service.llm.routing.context;

import com.google.adk.models.LlmRequest;

/**
 * Strategy interface for estimating token count from an {@link LlmRequest}.
 *
 * <p><strong>Note:</strong> Implementations provide estimated approximations for
 * context capacity planning (e.g. minContextWindowTokens), NOT exact billing counts.</p>
 */
public interface ContextTokenEstimator {

    /**
     * Estimates the total token count across the entire context in {@code request}.
     *
     * @param request the LLM request (may be null)
     * @return non-negative estimated token count
     */
    long estimate(LlmRequest request);
}
