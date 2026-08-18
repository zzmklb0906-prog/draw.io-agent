package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Requirement policy for {@code agent_analyst}.
 *
 * <p><strong>Responsibilities:</strong> User intent comprehension, requirement clarification,
 * drawing brief generation, and strict JSON output formatting.
 * High demands for instruction following and structured output, moderate reasoning demand,
 * low tool and coding demand.</p>
 */
@Component
public class AnalystAgentRequirementPolicy implements AgentRequirementPolicy {

    @Override
    public boolean supports(String agentName) {
        return "agent_analyst".equalsIgnoreCase(agentName);
    }

    @Override
    public RoutingRequirement adjust(RoutingContext context, RoutingRequirement base) {
        int reasoning = Math.min(100, Math.max(base.reasoningRequired(), 60));
        int instruction = Math.min(100, Math.max(base.instructionFollowingRequired(), 90));
        int coding = Math.min(100, Math.max(base.codingRequired(), 20));
        int structured = Math.min(100, Math.max(base.structuredOutputRequired(), 88));
        int toolCalling = Math.min(100, Math.max(base.toolCallingRequired(), 20));
        long expectedOutput = Math.min(base.expectedOutputTokens(), 4096L);
        if (expectedOutput <= 0) {
            expectedOutput = 4096L;
        }

        List<String> adjustments = new ArrayList<>(base.evidence().adjustments());
        adjustments.add("agent_analyst: high instruction-following (90) & structured JSON (88)");

        RequirementEvidence evidence = new RequirementEvidence(
                base.evidence().taskSignals(),
                base.evidence().matchedPatterns(),
                adjustments,
                base.evidence().estimatedContextTokens(),
                expectedOutput
        );

        long minContextTokens = base.evidence().estimatedContextTokens() + expectedOutput + 2048L;

        return new RoutingRequirement(
                base.taskType(),
                reasoning,
                instruction,
                coding,
                structured,
                toolCalling,
                base.visionRequired(),
                minContextTokens,
                expectedOutput,
                context.agentName(),
                evidence
        );
    }
}
