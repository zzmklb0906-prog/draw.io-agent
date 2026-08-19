package cn.bugstack.ai.domain.agent.service.agent.routing;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters viable {@link AgentProfile} candidates against {@link AgentRequirement} hard constraints.
 */
@Slf4j
@Component
public class AgentSelector {

    private final AgentRegistry agentRegistry;

    public AgentSelector(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry != null ? agentRegistry : new DefaultAgentRegistry();
    }

    public AgentSelector() {
        this(new DefaultAgentRegistry());
    }

    /**
     * Selects all candidate agents capable of handling the specified requirements.
     */
    public List<AgentProfile> select(AgentRequirement requirement) {
        if (requirement == null) {
            return List.of();
        }

        List<AgentProfile> enabledAgents = agentRegistry.getEnabledAgents();
        List<AgentProfile> candidates = new ArrayList<>();

        for (AgentProfile agent : enabledAgents) {
            if (!agent.enabled()) {
                continue;
            }

            // Check capability overlap
            boolean hasCapabilityMatch = agent.capabilities().stream()
                    .anyMatch(requirement.requiredCapabilities()::contains);

            // Check task type overlap
            boolean hasTaskMatch = agent.supportedTasks().stream()
                    .anyMatch(requirement.taskTypes()::contains);

            if (hasCapabilityMatch || hasTaskMatch) {
                candidates.add(agent);
            }
        }

        log.debug("AgentSelector matched {} candidate agents for requirement: {}", candidates.size(), requirement.requiredCapabilities());
        return candidates;
    }
}
