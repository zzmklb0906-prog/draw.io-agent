package cn.bugstack.ai.domain.agent.service.agent.routing;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;

import java.util.Set;

/**
 * Universal Agent Profile.
 *
 * <p>Declarative capability descriptor representing an Agent in the Universal Agent Ecosystem.</p>
 */
public record AgentProfile(
        String agentId,
        String name,
        String description,
        Set<AgentCapability> capabilities,
        Set<TaskType> supportedTasks,
        Set<String> requiredTools,
        boolean enabled
) {
    public AgentProfile {
        capabilities = capabilities != null ? Set.copyOf(capabilities) : Set.of();
        supportedTasks = supportedTasks != null ? Set.copyOf(supportedTasks) : Set.of();
        requiredTools = requiredTools != null ? Set.copyOf(requiredTools) : Set.of();
    }

    public boolean supportsCapability(AgentCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean supportsTask(TaskType taskType) {
        return supportedTasks.contains(taskType);
    }
}
