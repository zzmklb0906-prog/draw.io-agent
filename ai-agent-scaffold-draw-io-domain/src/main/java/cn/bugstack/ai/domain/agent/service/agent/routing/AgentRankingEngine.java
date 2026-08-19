package cn.bugstack.ai.domain.agent.service.agent.routing;

import java.util.List;

/**
 * Universal Agent Ranking Engine.
 *
 * <p>Ranks candidate {@link AgentProfile} models based on multi-dimensional requirement fit.</p>
 */
public interface AgentRankingEngine {

    /**
     * Ranks candidate agents for a given agent requirement.
     *
     * @param requirement agent capability requirement
     * @param candidates candidate agents
     * @return sorted list of ranked agents (highest score first)
     */
    List<RankedAgent> rank(AgentRequirement requirement, List<AgentProfile> candidates);
}
