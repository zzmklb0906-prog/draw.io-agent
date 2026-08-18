package cn.bugstack.ai.domain.agent.service.llm.catalog;

/**
 * Model Token Limits.
 *
 * <p>Specifies the model's context window capacity and maximum output generation limit in tokens.</p>
 */
public record ModelLimits(
        long contextWindowTokens,
        long maxOutputTokens
) {}
