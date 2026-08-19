package cn.bugstack.ai.domain.agent.service.agent.routing;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;

import java.util.Set;

/**
 * Requirement descriptor expressing what kind of Agent capabilities are demanded by a user request.
 *
 * <p>Answers <strong>WHICH AGENT CAPABILITY IS NEEDED?</strong>
 * Strictly decoupled from specific agent IDs and model names.</p>
 */
public record AgentRequirement(
        Set<TaskType> taskTypes,
        Set<AgentCapability> requiredCapabilities,
        boolean requiresTools,
        boolean requiresPlanning,
        String rawPrompt
) {
    public AgentRequirement {
        taskTypes = taskTypes != null ? Set.copyOf(taskTypes) : Set.of();
        requiredCapabilities = requiredCapabilities != null ? Set.copyOf(requiredCapabilities) : Set.of();
    }

    public static AgentRequirement generalChat(String rawPrompt) {
        return new AgentRequirement(
                Set.of(TaskType.GENERAL_CHAT),
                Set.of(AgentCapability.GENERAL_CHAT),
                false,
                false,
                rawPrompt
        );
    }
}
