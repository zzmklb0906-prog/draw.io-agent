package cn.bugstack.ai.domain.agent.service.llm.routing.candidate;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;

import java.util.List;

/**
 * Capability-aware Model Filter.
 *
 * <p>Filters candidate {@link ModelProfile}s against capability requirements
 * (Tool Calling, Vision, Structured Output, Context Window, Enabled status).</p>
 */
public interface ModelCapabilityFilter {

    /**
     * Filters candidate models against the specified requirement.
     *
     * @param requirement the task capability requirements (nullable)
     * @param models candidate models list (nullable)
     * @return filtered list of compliant candidate models (never null)
     */
    List<ModelProfile> filter(RoutingRequirement requirement, List<ModelProfile> models);
}
