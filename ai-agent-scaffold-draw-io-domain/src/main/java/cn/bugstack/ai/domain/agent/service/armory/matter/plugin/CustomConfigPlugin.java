package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.service.chat.CustomApiConfigManager;
import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderProperties;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderRegistryService;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service("customConfigPlugin")
public class CustomConfigPlugin extends BasePlugin {
    private final ModelRoutingService modelRoutingService;
    private final LightweightMonitorService monitorService;
    private final ModelProviderRegistryService providerRegistryService;
    private final cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory routingContextFactory;
    private final cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService requirementService;
    private final cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService constraintFilteringService;
    private final cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService dynamicRankingService;

    public CustomConfigPlugin(ModelRoutingService modelRoutingService,
                              LightweightMonitorService monitorService,
                              ModelProviderRegistryService providerRegistryService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory routingContextFactory,
                              cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService requirementService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService constraintFilteringService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService dynamicRankingService) {
        super("CustomConfigPlugin");
        this.modelRoutingService = modelRoutingService;
        this.monitorService = monitorService;
        this.providerRegistryService = providerRegistryService;
        this.routingContextFactory = routingContextFactory;
        this.requirementService = requirementService;
        this.constraintFilteringService = constraintFilteringService;
        this.dynamicRankingService = dynamicRankingService;
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder requestBuilder) {
        String sessionId = context.sessionId();
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.getConfig(sessionId);

        boolean explicitModel = config != null && config.isCustomModelSelected() && StringUtils.isNotBlank(config.getModel());
        String finalModel = requestBuilder.build().model().orElse("");
        String activeAgent = monitorService.activeAgentName(context.invocationId());
        String shadowRecommendedModel = null;
        Double shadowTopScore = null;

        // Shadow Mode: Phase 3 Requirement, Phase 4 Filter & Phase 5 Ranking (observation only, does NOT alter model selection)
        try {
            var routingContext = routingContextFactory.create(
                    requestBuilder.build(),
                    activeAgent,
                    "UNKNOWN",
                    explicitModel,
                    explicitModel ? config.getModel() : null
            );
            var reqOpt = requirementService.tryAnalyze(routingContext);
            if (reqOpt.isPresent()) {
                var requirement = reqOpt.get();
                var filterResult = constraintFilteringService.filter(requirement);
                var rankingResult = dynamicRankingService.rank(requirement, filterResult);

                if (rankingResult.topCandidate().isPresent()) {
                    var top = rankingResult.topCandidate().get();
                    shadowRecommendedModel = top.model().modelName();
                    shadowTopScore = top.totalScore();
                }

                var rankingSummary = rankingResult.rankedCandidates().stream()
                        .map(cs -> String.format("%s:%.2f", cs.model().modelName(), cs.totalScore()))
                        .toList();

                log.debug("Shadow Dynamic Ranking [invocationId={}]: taskType={}, recommended={}, topScore={}, ranking={}",
                        context.invocationId(), requirement.taskType(), shadowRecommendedModel, shadowTopScore, rankingSummary);
            }
        } catch (Exception e) {
            log.warn("Shadow dynamic ranking skipped due to exception: {}", e.getMessage());
        }

        if (!explicitModel) {
            ModelRoutingService.Decision decision = modelRoutingService.route(requestBuilder.build());
            String selected = decision.model() != null ? decision.model() : finalModel;
            if (decision.model() != null) {
                requestBuilder.model(decision.model());
                finalModel = decision.model();
            }
            monitorService.modelRouted(
                    context.invocationId(),
                    monitorService.activeAgentName(context.invocationId()),
                    selected,
                    decision.reason(),
                    decision.complexity(),
                    false,
                    decision.narrative(),
                    decision.metrics(),
                    decision.matchedKeywords(),
                    decision.pipelineTrail()
            );
            log.info("Model Router invocationId={} model={} reason={} complexity={}", context.invocationId(), selected, decision.reason(), decision.complexity());
        } else if (config != null && StringUtils.isNotBlank(config.getModel())) {
            requestBuilder.model(config.getModel());
            finalModel = config.getModel();
            monitorService.modelRouted(
                    context.invocationId(),
                    monitorService.activeAgentName(context.invocationId()),
                    config.getModel(),
                    "USER_EXPLICIT",
                    0,
                    true,
                    "用户在工作台显式指定覆盖模型：" + config.getModel(),
                    Map.of("explicitModel", config.getModel()),
                    List.of(),
                    List.of(Map.of("tier", "Explicit User Selection", "status", "OVERRIDDEN", "detail", "用户前端直接选定模型"))
            );
        }

        // Record unified Shadow Routing Comparison
        cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource selectionSource =
                explicitModel ? cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource.USER_EXPLICIT
                        : cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource.LEGACY_ROUTER;

        Boolean matched = (shadowRecommendedModel != null && finalModel != null)
                ? shadowRecommendedModel.equalsIgnoreCase(finalModel)
                : null;

        var comparison = new cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison(
                finalModel,
                shadowRecommendedModel,
                matched,
                shadowTopScore,
                selectionSource
        );

        log.debug("Shadow Routing Comparison [invocationId={}]: actualModel={}, recommendedModel={}, matched={}, recommendedScore={}, actualSource={}",
                context.invocationId(), comparison.actualModel(), comparison.recommendedModel(), comparison.matched(), comparison.recommendedScore(), comparison.actualSource());

        // 根据确定后的 finalModel 自动匹配多厂商 Provider 三元组 (BaseUrl, ApiKey, CompletionsPath)
        ModelProviderProperties.ProviderConfig providerConfig = providerRegistryService.findProviderConfig(finalModel);

        GenerateContentConfig.Builder configBuilder = requestBuilder.config().isPresent() ?
                requestBuilder.config().get().toBuilder() : GenerateContentConfig.builder();

        HttpOptions.Builder httpOptionsBuilder = configBuilder.build().httpOptions().isPresent() ?
                configBuilder.build().httpOptions().get().toBuilder() : HttpOptions.builder();

        Map<String, String> headers = new HashMap<>();
        if (httpOptionsBuilder.build().headers().isPresent()) {
            headers.putAll(httpOptionsBuilder.build().headers().get());
        }

        // 1. 优先采用 Provider 注册中心自动寻路得到的厂商属性
        if (providerConfig != null) {
            if (StringUtils.isNotBlank(providerConfig.getBaseUrl())) {
                headers.put("X-Custom-Base-Url", providerConfig.getBaseUrl());
            }
            if (StringUtils.isNotBlank(providerConfig.getApiKey())) {
                headers.put("X-Custom-Api-Key", providerConfig.getApiKey());
            }
            if (StringUtils.isNotBlank(providerConfig.getCompletionsPath())) {
                headers.put("X-Custom-Completions-Path", providerConfig.getCompletionsPath());
            }
        }

        // 2. 最高优先级：如果用户在前端手填覆盖了自定义三元组，则覆盖
        if (config != null) {
            if (StringUtils.isNotBlank(config.getBaseUrl())) {
                headers.put("X-Custom-Base-Url", config.getBaseUrl());
            }
            if (StringUtils.isNotBlank(config.getApiKey())) {
                headers.put("X-Custom-Api-Key", config.getApiKey());
            }
            if (StringUtils.isNotBlank(config.getCompletionsPath())) {
                headers.put("X-Custom-Completions-Path", config.getCompletionsPath());
            }
            if (config.isCustomModelSelected()) {
                headers.put("X-Custom-Model-Selected", "true");
            }
        }

        httpOptionsBuilder.headers(headers);
        configBuilder.httpOptions(httpOptionsBuilder.build());
        requestBuilder.config(configBuilder.build());

        return super.beforeModelCallback(context, requestBuilder);
    }
}
