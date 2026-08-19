package cn.bugstack.ai.domain.agent.service.llm.routing.context;

import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.RoutingTextInput;
import com.google.adk.models.LlmRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Factory for creating immutable {@link RoutingContext} instances.
 */
@Component
public class RoutingContextFactory {

    private final LatestUserMessageExtractor extractor;
    private final ContextTokenEstimator tokenEstimator;

    public RoutingContextFactory(LatestUserMessageExtractor extractor,
                                 ContextTokenEstimator tokenEstimator) {
        this.extractor = extractor;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * Creates a default {@link RoutingContext} from an {@link LlmRequest}.
     */
    public RoutingContext create(LlmRequest request) {
        return create(request, "unknown", "UNKNOWN", false, null);
    }

    /**
     * Creates a {@link RoutingContext} with agentName and default stage/explicit flags.
     */
    public RoutingContext create(LlmRequest request, String agentName) {
        return create(request, agentName, "UNKNOWN", false, null);
    }

    /**
     * Creates a fully specified {@link RoutingContext}.
     */
    public RoutingContext create(LlmRequest request,
                                 String agentName,
                                 String workflowStage,
                                 boolean explicitModel,
                                 String explicitModelName) {
        RoutingTextInput input = extractor.buildRoutingInput(request);
        long estimatedTokens = tokenEstimator.estimate(request);
        String resolvedAgent = StringUtils.isNotBlank(agentName) ? agentName.trim() : "unknown";
        String resolvedStage = StringUtils.isNotBlank(workflowStage) ? workflowStage.trim() : "UNKNOWN";
        boolean hasToolContext = request != null
                && request.config().isPresent()
                && request.config().get().tools().isPresent()
                && !request.config().get().tools().get().isEmpty();

        return new RoutingContext(
                request,
                input.latestUserText(),
                input.totalContextChars(),
                estimatedTokens,
                resolvedAgent,
                resolvedStage,
                explicitModel,
                explicitModelName,
                hasToolContext
        );
    }
}
