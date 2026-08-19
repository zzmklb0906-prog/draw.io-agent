package cn.bugstack.ai.domain.agent.service.agent.routing;

/**
 * Analyzes incoming user requests into an abstract {@link AgentRequirement}.
 */
public interface AgentRequirementAnalyzer {

    /**
     * Analyzes raw user prompt to determine required agent capabilities.
     *
     * @param prompt user prompt text
     * @return deduced {@link AgentRequirement}
     */
    AgentRequirement analyze(String prompt);
}
