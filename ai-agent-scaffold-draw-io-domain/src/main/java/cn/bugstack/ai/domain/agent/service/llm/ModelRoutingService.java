package cn.bugstack.ai.domain.agent.service.llm;

import cn.bugstack.ai.domain.agent.service.llm.strategy.IModelRouterStrategy;
import com.google.adk.models.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Universal Multi-Strategy Model Routing Service.
 * Supports rule-based, heuristic semantic router, rule-based classifier, and composite routing strategies.
 */
@Slf4j
@Service
public class ModelRoutingService {

    private final boolean enabled;
    private final String strategyName;
    private final String fastModel;
    private final String balancedModel;
    private final String reasoningModel;
    private final Map<String, IModelRouterStrategy> strategyMap;
    private final cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory contextFactory;

    public ModelRoutingService(@Value("${ai.agent.model-routing.enabled:true}") boolean enabled,
                               @Value("${ai.agent.model-routing.strategy:composite}") String strategyName,
                               @Value("${ai.agent.model-routing.fast-model:}") String fastModel,
                               @Value("${ai.agent.model-routing.balanced-model:}") String balancedModel,
                               @Value("${ai.agent.model-routing.reasoning-model:}") String reasoningModel,
                               List<IModelRouterStrategy> strategies,
                               cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory contextFactory) {
        this.enabled = enabled;
        this.strategyName = strategyName;
        this.fastModel = fastModel;
        this.balancedModel = balancedModel;
        this.reasoningModel = reasoningModel;
        this.strategyMap = strategies.stream().collect(Collectors.toMap(IModelRouterStrategy::strategyName, Function.identity(), (a, b) -> a));
        this.contextFactory = contextFactory != null ? contextFactory : createDefaultFactory();
    }

    public ModelRoutingService(boolean enabled,
                               String strategyName,
                               String fastModel,
                               String balancedModel,
                               String reasoningModel,
                               List<IModelRouterStrategy> strategies) {
        this(enabled, strategyName, fastModel, balancedModel, reasoningModel, strategies, null);
    }

    /**
     * High-level entry point accepting an explicit {@link cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext}.
     */
    public Decision route(cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext context) {
        if (!enabled) {
            return new Decision(null, "DISABLED", 0);
        }

        IModelRouterStrategy strategy = strategyMap.getOrDefault(strategyName.toLowerCase(), strategyMap.get("composite"));
        if (strategy == null) {
            return new Decision(null, "STRATEGY_NOT_FOUND", 2);
        }

        Decision decision = strategy.route(context, fastModel, balancedModel, reasoningModel);
        log.info("Model Routing decision using strategy [{}]: model={} reason={} complexity={}",
                strategy.strategyName(), decision.model(), decision.reason(), decision.complexity());
        return decision;
    }

    /**
     * Legacy entry point transforming {@link LlmRequest} into {@link cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext}.
     */
    public Decision route(LlmRequest request) {
        cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext context = contextFactory.create(request);
        return route(context);
    }

    private static cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory createDefaultFactory() {
        var extractor = new cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor();
        var tokenEstimator = new cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator(extractor);
        return new cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory(extractor, tokenEstimator);
    }

    public record Decision(
            String model,
            String reason,
            int complexity,
            String narrative,
            Map<String, Object> metrics,
            List<String> matchedKeywords,
            List<Map<String, Object>> pipelineTrail
    ) {
        public Decision(String model, String reason, int complexity) {
            this(model, reason, complexity, "", Map.of(), List.of(), List.of());
        }
    }
}
