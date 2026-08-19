package cn.bugstack.ai.domain.agent.service.llm.routing.decision;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.RankedModel;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;

import java.util.List;

/**
 * Policy governing whether Dynamic Model Ranking outcome is permitted to execute.
 */
public interface RoutingPolicy {

    /**
     * Evaluates whether dynamic routing takeover is permissible for this invocation.
     *
     * @param context invocation context
     * @param requirement task requirements
     * @param rankedModels dynamic ranked candidate models (top1 first)
     * @param legacySelectedModel model chosen by legacy composite router
     * @return true if dynamic takeover is allowed, false to fallback to legacy
     */
    boolean allowDynamicRouting(
            RoutingContext context,
            RoutingRequirement requirement,
            List<RankedModel> rankedModels,
            String legacySelectedModel
    );
}
