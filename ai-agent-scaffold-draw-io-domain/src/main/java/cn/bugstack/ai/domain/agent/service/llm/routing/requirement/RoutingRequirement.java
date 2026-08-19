package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

/**
 * Capability and Operational Requirements for an LLM Request.
 *
 * <p>Answers <strong>WHAT DOES THIS REQUEST REQUIRE?</strong>
 * Represents normalized capability requirements (0~100) and operational bounds (context window, output tokens, latency sensitivity).
 * Does NOT contain selected model names, vendor rankings, or candidate filtering logic.</p>
 */
public record RoutingRequirement(
        TaskType taskType,
        int reasoningRequired,
        int instructionFollowingRequired,
        int codingRequired,
        int structuredOutputRequired,
        int toolCallingRequired,
        boolean visionRequired,
        long minContextWindowTokens,
        long expectedOutputTokens,
        String agentName,
        RequirementEvidence evidence,
        LatencySensitivity latencySensitivity
) {
    public RoutingRequirement(
            TaskType taskType,
            int reasoningRequired,
            int instructionFollowingRequired,
            int codingRequired,
            int structuredOutputRequired,
            int toolCallingRequired,
            boolean visionRequired,
            long minContextWindowTokens,
            long expectedOutputTokens,
            String agentName,
            RequirementEvidence evidence
    ) {
        this(taskType, reasoningRequired, instructionFollowingRequired, codingRequired, structuredOutputRequired,
                toolCallingRequired, visionRequired, minContextWindowTokens, expectedOutputTokens, agentName, evidence, LatencySensitivity.NORMAL);
    }

    public boolean needToolCalling() {
        return toolCallingRequired > 0;
    }

    public boolean needStructuredOutput() {
        return structuredOutputRequired > 0;
    }

    public boolean needVision() {
        return visionRequired;
    }

    public boolean needLongContext() {
        return minContextWindowTokens > 16_384;
    }

    /**
     * Extracts estimated input token consumption for accurate cost estimation.
     * Distinct from {@link #minContextWindowTokens()} (hard capacity requirement with headroom).
     */
    public long estimatedInputTokens() {
        if (evidence != null && evidence.estimatedContextTokens() > 0) {
            return evidence.estimatedContextTokens();
        }
        return Math.max(500L, Math.min(minContextWindowTokens, 2048L));
    }

    public int estimatedComplexity() {
        if (reasoningRequired >= 65) return 3;
        if (reasoningRequired <= 25) return 1;
        return 2;
    }

    public static RoutingRequirement defaultRequirement(String agentName) {
        return new RoutingRequirement(
                TaskType.GENERAL_CHAT,
                30,
                50,
                20,
                0,
                0,
                false,
                4096L,
                2048L,
                agentName != null ? agentName : "unknown",
                RequirementEvidence.empty(),
                LatencySensitivity.NORMAL
        );
    }
}
