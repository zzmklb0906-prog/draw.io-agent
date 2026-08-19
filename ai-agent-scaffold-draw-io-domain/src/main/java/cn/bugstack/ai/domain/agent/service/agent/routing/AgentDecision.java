package cn.bugstack.ai.domain.agent.service.agent.routing;

import java.util.List;

/**
 * Top-tier Agent Routing Decision.
 *
 * <p>Answers <strong>WHICH AGENT SHOULD PROCESS THIS REQUEST?</strong></p>
 */
public record AgentDecision(
        String selectedAgentId,
        double confidence,
        String reason,
        List<String> backupAgents,
        boolean isFound
) {
    public AgentDecision {
        backupAgents = backupAgents != null ? List.copyOf(backupAgents) : List.of();
    }

    public static AgentDecision notFound(String reason) {
        return new AgentDecision(null, 0.0, reason, List.of(), false);
    }
}
