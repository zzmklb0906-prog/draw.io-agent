package cn.bugstack.ai.domain.agent.service.llm.routing.constraint;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;

import java.util.List;

/**
 * Hard Constraint Filter strategy interface.
 *
 * <p>Answers <strong>WHO IS INELIGIBLE?</strong> by evaluating candidate {@link ModelProfile}s
 * against hard requirements (e.g. vision support, context window capacity, max output budget).</p>
 */
public interface ModelConstraintFilter {

    /**
     * Filters candidate models against hard constraints in the requirement.
     *
     * @param requirement the request requirements (must not be null)
     * @param candidates the list of candidate model profiles to evaluate
     * @return an immutable {@link ModelFilterResult} containing accepted and rejected models
     */
    ModelFilterResult filter(RoutingRequirement requirement, List<ModelProfile> candidates);
}
