package cn.bugstack.ai.domain.agent.service.agent.routing;

import java.util.List;
import java.util.Optional;

/**
 * Universal Registry of available Autonomous Agents.
 */
public interface AgentRegistry {

    /**
     * Lists all registered agent profiles.
     */
    List<AgentProfile> getAgents();

    /**
     * Finds an agent by its unique identifier.
     */
    Optional<AgentProfile> find(String agentId);

    /**
     * Lists all active/enabled agent profiles.
     */
    List<AgentProfile> getEnabledAgents();

    /**
     * Registers or updates an agent profile.
     */
    void registerAgent(AgentProfile profile);
}
