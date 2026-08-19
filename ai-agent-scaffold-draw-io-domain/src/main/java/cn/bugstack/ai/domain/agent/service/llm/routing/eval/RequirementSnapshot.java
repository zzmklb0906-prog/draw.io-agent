package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

/**
 * Immutable snapshot of demand-side requirement metrics for offline evaluation.
 */
public record RequirementSnapshot(
        int reasoning,
        int instructionFollowing,
        int coding,
        int structuredOutput,
        int toolCalling,
        boolean visionRequired,
        long minContextWindowTokens,
        long expectedOutputTokens
) {}
