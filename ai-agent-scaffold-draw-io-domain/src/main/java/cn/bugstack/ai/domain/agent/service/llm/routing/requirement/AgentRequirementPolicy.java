package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;

/**
 * Agent-aware Requirement Adjustment Policy.
 *
 * <p>Adjusts baseline capability requirements based on the specific architectural responsibilities
 * of the currently active Agent in the workflow (e.g. analyst, drawer, reviewer).</p>
 */
public interface AgentRequirementPolicy {

    /**
     * Whether this policy applies to the given agent name.
     */
    boolean supports(String agentName);

    /**
     * Adjusts the baseline requirement based on agent specialization.
     *
     * @param context the invocation routing context
     * @param base the baseline requirement derived from task type
     * @return an adjusted requirement
     */
    RoutingRequirement adjust(RoutingContext context, RoutingRequirement base);
}
