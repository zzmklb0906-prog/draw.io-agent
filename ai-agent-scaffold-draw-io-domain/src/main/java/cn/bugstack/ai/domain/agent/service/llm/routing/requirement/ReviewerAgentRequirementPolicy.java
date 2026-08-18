package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Requirement policy for {@code agent_reviewer}.
 *
 * <p><strong>Responsibilities:</strong> Graph inspection, syntax verification,
 * and topological repair. High reasoning and structured output requirements.</p>
 */
@Component
public class ReviewerAgentRequirementPolicy implements AgentRequirementPolicy {

    @Override
    public boolean supports(String agentName) {
        return "agent_reviewer".equalsIgnoreCase(agentName);
    }

    @Override
    public RoutingRequirement adjust(RoutingContext context, RoutingRequirement base) {
        int reasoning = Math.min(100, Math.max(base.reasoningRequired(), 80));
        int instruction = Math.min(100, Math.max(base.instructionFollowingRequired(), 90));
        int coding = Math.min(100, Math.max(base.codingRequired(), 70));
        int structured = Math.min(100, Math.max(base.structuredOutputRequired(), 90));
        int toolCalling = Math.min(100, Math.max(base.toolCallingRequired(), 40));
        long expectedOutput = Math.max(base.expectedOutputTokens(), 4096L);

        List<String> adjustments = new ArrayList<>(base.evidence().adjustments());
        adjustments.add("agent_reviewer: high reasoning (80) & structured syntax verification (90)");

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
