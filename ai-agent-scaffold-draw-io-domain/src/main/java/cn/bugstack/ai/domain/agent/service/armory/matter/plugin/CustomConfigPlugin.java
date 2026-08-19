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
    private final cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationService evaluationService;

    public CustomConfigPlugin(ModelRoutingService modelRoutingService,
                              LightweightMonitorService monitorService,
                              ModelProviderRegistryService providerRegistryService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory routingContextFactory,
                              cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService requirementService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService constraintFilteringService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService dynamicRankingService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationService evaluationService) {
        super("CustomConfigPlugin");
        this.modelRoutingService = modelRoutingService;
        this.monitorService = monitorService;
        this.providerRegistryService = providerRegistryService;
        this.routingContextFactory = routingContextFactory;
        this.requirementService = requirementService;
        this.constraintFilteringService = constraintFilteringService;
        this.dynamicRankingService = dynamicRankingService;
        this.evaluationService = evaluationService;
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder requestBuilder) {
        String sessionId = context.sessionId();
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.getConfig(sessionId);

        boolean explicitModel = config != null && config.isCustomModelSelected() && StringUtils.isNotBlank(config.getModel());
        String defaultModel = requestBuilder.build().model().orElse("");
        String activeAgent = monitorService.activeAgentName(context.invocationId());
        String shadowRecommendedModel = null;
        Double shadowTopScore = null;

        cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext routingContext = null;
        cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement routingRequirement = null;
        cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelFilterResult modelFilterResult = null;
        cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RankingResult modelRankingResult = null;

        // Shadow Mode: Phase 3 Requirement, Phase 4 Filter & Phase 5 Ranking (observation only, does NOT alter model selection)
        try {
            routingContext = routingContextFactory.create(
                    requestBuilder.build(),
                    activeAgent,
                    "UNKNOWN",
                    explicitModel,
                    explicitModel ? config.getModel() : null
            );
            var reqOpt = requirementService.tryAnalyze(routingContext);
            if (reqOpt.isPresent()) {
                routingRequirement = reqOpt.get();
                modelFilterResult = constraintFilteringService.filter(routingRequirement);
                modelRankingResult = dynamicRankingService.rank(routingRequirement, modelFilterResult);

                if (modelRankingResult.topCandidate().isPresent()) {
                    var top = modelRankingResult.topCandidate().get();
                    shadowRecommendedModel = top.model().modelName();
                    shadowTopScore = top.totalScore();
                }

                var rankingSummary = modelRankingResult.rankedCandidates().stream()
                        .map(cs -> String.format("%s:%.2f", cs.model().modelName(), cs.totalScore()))
                        .toList();

                log.debug("Shadow Dynamic Ranking [invocationId={}]: taskType={}, recommended={}, topScore={}, ranking={}",
                        context.invocationId(), routingRequirement.taskType(), shadowRecommendedModel, shadowTopScore, rankingSummary);
            }
        } catch (Exception e) {
            log.warn("Shadow dynamic ranking skipped due to exception: {}", e.getMessage());
        }

        // Actual Production Model Selection (Legacy Router or Explicit User Selection)
        String actualModel;
        cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource selectionSource;

        if (!explicitModel) {
            ModelRoutingService.Decision decision = modelRoutingService.route(requestBuilder.build());
            actualModel = decision.model() != null ? decision.model() : defaultModel;
            selectionSource = cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource.LEGACY_ROUTER;

            if (decision.model() != null) {
                requestBuilder.model(decision.model());
            }
            monitorService.modelRouted(
                    context.invocationId(),
                    monitorService.activeAgentName(context.invocationId()),
                    actualModel,
                    decision.reason(),
                    decision.complexity(),
                    false,
                    decision.narrative(),
                    decision.metrics(),
                    decision.matchedKeywords(),
                    decision.pipelineTrail()
            );
            log.info("Model Router invocationId={} model={} reason={} complexity={}", context.invocationId(), actualModel, decision.reason(), decision.complexity());
        } else {
            actualModel = config.getModel();
            selectionSource = cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource.USER_EXPLICIT;

            requestBuilder.model(actualModel);
            monitorService.modelRouted(
                    context.invocationId(),
                    monitorService.activeAgentName(context.invocationId()),
                    actualModel,
                    "USER_EXPLICIT",
                    0,
                    true,
                    "用户在工作台显式指定覆盖模型：" + actualModel,
                    Map.of("explicitModel", actualModel),
                    List.of(),
                    List.of(Map.of("tier", "Explicit User Selection", "status", "OVERRIDDEN", "detail", "用户前端直接选定模型"))
            );
        }

        // Record unified Shadow Routing Comparison & Phase 6 Structured Evaluation Telemetry
        Boolean matched = (shadowRecommendedModel != null && actualModel != null)
                ? shadowRecommendedModel.equalsIgnoreCase(actualModel)
                : null;

        var comparison = new cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison(
                actualModel,
                shadowRecommendedModel,
                matched,
                shadowTopScore,
                selectionSource
        );

        log.debug("Shadow Routing Comparison [invocationId={}]: actualModel={}, recommendedModel={}, matched={}, recommendedScore={}, actualSource={}",
                context.invocationId(), comparison.actualModel(), comparison.recommendedModel(), comparison.matched(), comparison.recommendedScore(), comparison.actualSource());

        try {
            var evalRecord = evaluationService.buildRecord(
                    context.invocationId(),
                    routingContext,
                    routingRequirement,
                    modelFilterResult,
                    modelRankingResult,
                    comparison
            );
            evaluationService.tryRecord(evalRecord);
        } catch (Exception e) {
            log.warn("Shadow evaluation recording skipped due to exception: {}", e.getMessage());
        }

        // 根据确定后的 actualModel 自动匹配多厂商 Provider 三元组 (BaseUrl, ApiKey, CompletionsPath)
        ModelProviderProperties.ProviderConfig providerConfig = providerRegistryService.findProviderConfig(actualModel);

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
