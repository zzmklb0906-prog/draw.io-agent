package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;

/**
 * Requirement Analyzer strategy interface.
 *
 * <p>Answers <strong>WHAT DOES THIS REQUEST REQUIRE?</strong> by producing a normalized
 * {@link RoutingRequirement} from an incoming {@link RoutingContext}.</p>
 */
public interface RoutingRequirementAnalyzer {

    /**
     * Analyzes the invocation context to extract operational and capability requirements.
     *
     * @param context the routing context containing latest user text and metadata
     * @return the structured requirement (never null)
     */
    RoutingRequirement analyze(RoutingContext context);
}
