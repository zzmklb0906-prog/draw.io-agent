package cn.bugstack.ai.domain.agent.service.llm.routing.decision;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.RankedModel;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default implementation of {@link RoutingPolicy}.
 *
 * <p><strong>Takeover Rules:</strong>
 * <ol>
 *   <li><strong>Explicit Override:</strong> If the caller explicitly specifies a model, dynamic takeover is rejected.</li>
 *   <li><strong>Empty Ranking:</strong> If no eligible ranked models exist, dynamic takeover is rejected.</li>
 *   <li><strong>Routing Mode Guard:</strong>
 *     <ul>
 *       <li>{@link RoutingMode#LEGACY}: Never allows dynamic.</li>
 *       <li>{@link RoutingMode#SHADOW}: Never allows dynamic (shadow observation only).</li>
 *       <li>{@link RoutingMode#DYNAMIC}: Allows dynamic routing for viable candidates.</li>
 *       <li>{@link RoutingMode#CANARY}: Deterministic hash partition {@code (abs(key.hashCode) % 100 < canaryPercentage)}.</li>
 *     </ul>
 *   </li>
 * </ol>
 * </p>
 */
@Component
public class DefaultRoutingPolicy implements RoutingPolicy {

    private final RoutingDecisionProperties properties;

    public DefaultRoutingPolicy(RoutingDecisionProperties properties) {
        this.properties = properties != null ? properties : new RoutingDecisionProperties();
        this.properties.validate();
    }

    public DefaultRoutingPolicy() {
        this(new RoutingDecisionProperties());
    }

    @Override
    public boolean allowDynamicRouting(RoutingContext context,
                                       RoutingRequirement requirement,
                                       List<RankedModel> rankedModels,
                                       String legacySelectedModel) {
        // Rule 1: Explicit user model override always bypasses dynamic takeover
        if (properties.isAllowExplicitOverride() && context != null && StringUtils.isNotBlank(context.explicitModelName())) {
            return false;
        }

        // Rule 2: Require at least one ranked candidate
        if (rankedModels == null || rankedModels.isEmpty()) {
            return false;
        }

        // Rule 3: Routing mode check
        RoutingMode mode = properties.getMode() != null ? properties.getMode() : RoutingMode.SHADOW;
        return switch (mode) {
            case LEGACY, SHADOW -> false;
            case DYNAMIC -> true;
            case CANARY -> evaluateCanary(context, properties.getCanaryPercentage());
        };
    }

    private boolean evaluateCanary(RoutingContext context, int percentage) {
        if (percentage <= 0) {
            return false;
        }
        if (percentage >= 100) {
            return true;
        }

        String key = resolveCanaryKey(context);
        int bucket = Math.abs(key.hashCode()) % 100;
        return bucket < percentage;
    }

    private String resolveCanaryKey(RoutingContext context) {
        if (context == null) {
            return "default-canary-key";
        }
        if (StringUtils.isNotBlank(context.requestId())) {
            return context.requestId();
        }
        String text = context.latestUserText() != null ? context.latestUserText() : "";
        String agent = context.agentName() != null ? context.agentName() : "";
        return agent + ":" + text;
    }
}
