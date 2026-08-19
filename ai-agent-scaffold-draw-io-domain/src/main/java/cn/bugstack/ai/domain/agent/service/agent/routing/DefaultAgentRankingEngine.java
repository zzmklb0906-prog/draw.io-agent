package cn.bugstack.ai.domain.agent.service.agent.routing;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default implementation of {@link AgentRankingEngine}.
 *
 * <p><strong>Explainable Scoring Formula:</strong>
 * {@code Final Score = (Capability Match * 0.5) + (Task Match * 0.3) + (Tool Match * 0.2)}
 * </p>
 */
@Component
public class DefaultAgentRankingEngine implements AgentRankingEngine {

    private static final double CAPABILITY_WEIGHT = 0.50;
    private static final double TASK_WEIGHT = 0.30;
    private static final double TOOL_WEIGHT = 0.20;

    @Override
    public List<RankedAgent> rank(AgentRequirement requirement, List<AgentProfile> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RankedAgent> rankedList = new ArrayList<>();

        for (AgentProfile agent : candidates) {
            if (agent == null || !agent.enabled()) {
                continue;
            }

            double capScore = calculateCapabilityScore(requirement, agent);
            double taskScore = calculateTaskScore(requirement, agent);
            double toolScore = calculateToolScore(requirement, agent);

            double finalScore = Math.round(((capScore * CAPABILITY_WEIGHT)
                    + (taskScore * TASK_WEIGHT)
                    + (toolScore * TOOL_WEIGHT)) * 1000.0) / 1000.0;

            String reason = String.format("Score=%.3f (Cap=%.2f*0.5 + Task=%.2f*0.3 + Tool=%.2f*0.2)",
                    finalScore, capScore, taskScore, toolScore);

            rankedList.add(new RankedAgent(agent, finalScore, capScore, taskScore, toolScore, reason));
        }

        // Deterministic sorting: Highest score first, then tie-break by agentId
        rankedList.sort(Comparator
                .comparingDouble(RankedAgent::score).reversed()
                .thenComparing(r -> r.profile().agentId()));

        return rankedList;
    }

    private double calculateCapabilityScore(AgentRequirement requirement, AgentProfile agent) {
        if (requirement == null || requirement.requiredCapabilities().isEmpty()) {
            return 0.5;
        }

        long matched = requirement.requiredCapabilities().stream()
                .filter(agent::supportsCapability)
                .count();

        return (double) matched / requirement.requiredCapabilities().size();
    }

    private double calculateTaskScore(AgentRequirement requirement, AgentProfile agent) {
        if (requirement == null || requirement.taskTypes().isEmpty()) {
            return 0.5;
        }

        long matched = requirement.taskTypes().stream()
                .filter(agent::supportsTask)
                .count();

        return (double) matched / requirement.taskTypes().size();
    }

    private double calculateToolScore(AgentRequirement requirement, AgentProfile agent) {
        if (requirement == null || !requirement.requiresTools()) {
            return 1.0;
        }
        return !agent.requiredTools().isEmpty() ? 1.0 : 0.4;
    }
}
