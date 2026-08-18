package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Requirement policy for {@code agent_drawer}.
 *
 * <p><strong>Responsibilities:</strong> Autonomous capability execution, NDJSON streaming,
 * Draw.io XML generation, and topological alignment.
 * Extremely high demand for structured output and tool calling, high instruction following,
 * moderate-high reasoning, and elevated output token budget.</p>
 */
@Component
public class DrawerAgentRequirementPolicy implements AgentRequirementPolicy {

    @Override
    public boolean supports(String agentName) {
        return "agent_drawer".equalsIgnoreCase(agentName);
    }

    @Override
    public RoutingRequirement adjust(RoutingContext context, RoutingRequirement base) {
        int reasoning = Math.min(100, Math.max(base.reasoningRequired(), 65));
        int instruction = Math.min(100, Math.max(base.instructionFollowingRequired(), 95));
        int coding = Math.min(100, Math.max(base.codingRequired(), 55));
        int structured = Math.min(100, Math.max(base.structuredOutputRequired(), 98));
        int toolCalling = Math.min(100, Math.max(base.toolCallingRequired(), 88));
        long expectedOutput = Math.max(base.expectedOutputTokens(), 16384L);

        List<String> adjustments = new ArrayList<>(base.evidence().adjustments());
        adjustments.add("agent_drawer: max structured output (98), high tool calling (88), elevated token budget (16384)");

        RequirementEvidence evidence = new RequirementEvidence(
                base.evidence().taskSignals(),
                base.evidence().matchedPatterns(),
                adjustments,
                base.evidence().estimatedContextTokens(),
                expectedOutput
        );

        long minContextTokens = base.evidence().estimatedContextTokens() + expectedOutput + 4096L;

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
