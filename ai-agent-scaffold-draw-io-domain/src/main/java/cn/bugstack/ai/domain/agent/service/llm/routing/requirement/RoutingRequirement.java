package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

/**
 * Capability and Operational Requirements for an LLM Request.
 *
 * <p>Answers <strong>WHAT DOES THIS REQUEST REQUIRE?</strong>
 * Represents normalized capability requirements (0~100) and operational bounds (context window, output tokens).
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
        RequirementEvidence evidence
) {
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
                RequirementEvidence.empty()
        );
    }
}
