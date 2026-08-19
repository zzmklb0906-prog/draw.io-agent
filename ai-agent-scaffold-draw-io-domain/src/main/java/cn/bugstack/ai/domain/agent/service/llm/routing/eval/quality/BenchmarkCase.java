package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;

import java.util.Set;

/**
 * Immutable offline benchmark evaluation case.
 */
public record BenchmarkCase(
        String caseId,
        TaskType taskType,
        String agentName,
        String prompt,
        BenchmarkExpectedOutput expected,
        GroundTruthLevel groundTruthLevel,
        Set<String> tags
) {
    public BenchmarkCase {
        tags = tags != null ? Set.copyOf(tags) : Set.of();
    }
}
