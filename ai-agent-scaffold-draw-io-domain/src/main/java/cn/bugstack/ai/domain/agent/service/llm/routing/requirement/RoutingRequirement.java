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
) {}
