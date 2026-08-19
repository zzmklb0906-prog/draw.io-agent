package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

/**
 * Aggregated statistics for requirement dimensions (reasoning, instruction following, coding, structured output, tool calling).
 */
public record RequirementDimensionStatistics(
        long sampleCount,
        double avgReasoning,
        double avgInstructionFollowing,
        double avgCoding,
        double avgStructuredOutput,
        double avgToolCalling,
        double highDemandReasoningRate,
        double highDemandInstructionFollowingRate,
        double highDemandCodingRate,
        double highDemandStructuredOutputRate,
        double highDemandToolCallingRate
) {}
