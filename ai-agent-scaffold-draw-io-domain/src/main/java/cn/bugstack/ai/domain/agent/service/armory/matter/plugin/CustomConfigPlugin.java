package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.service.chat.CustomApiConfigManager;
import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelTier;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderProperties;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderRegistryService;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintViolation;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelFilterResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationService;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RankingResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison.SelectionSource;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service("customConfigPlugin")
public class CustomConfigPlugin extends BasePlugin {

    private final ModelRoutingService modelRoutingService;
    private final LightweightMonitorService monitorService;
    private final ModelProviderRegistryService providerRegistryService;
    private final RoutingContextFactory routingContextFactory;
    private final RoutingRequirementService requirementService;
    private final ModelConstraintFilteringService constraintFilteringService;
    private final DynamicModelRankingService dynamicRankingService;
    private final RoutingEvaluationService evaluationService;
    private final ModelCatalogService modelCatalogService;
    private final boolean dynamicRoutingEnabled;

    @Autowired
    public CustomConfigPlugin(ModelRoutingService modelRoutingService,
                              LightweightMonitorService monitorService,
                              ModelProviderRegistryService providerRegistryService,
                              RoutingContextFactory routingContextFactory,
                              RoutingRequirementService requirementService,
                              ModelConstraintFilteringService constraintFilteringService,
                              DynamicModelRankingService dynamicRankingService,
                              RoutingEvaluationService evaluationService,
                              ModelCatalogService modelCatalogService,
                              @Value("${ai.agent.model-routing.dynamic-enabled:true}") boolean dynamicRoutingEnabled) {
        super("CustomConfigPlugin");
        this.modelRoutingService = modelRoutingService;
        this.monitorService = monitorService;
        this.providerRegistryService = providerRegistryService;
        this.routingContextFactory = routingContextFactory;
        this.requirementService = requirementService;
        this.constraintFilteringService = constraintFilteringService;
        this.dynamicRankingService = dynamicRankingService;
        this.evaluationService = evaluationService;
        this.modelCatalogService = modelCatalogService;
        this.dynamicRoutingEnabled = dynamicRoutingEnabled;
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

        RoutingContext routingContext = null;
        RoutingRequirement routingRequirement = null;
        ModelFilterResult modelFilterResult = null;
        RankingResult modelRankingResult = null;
        Throwable pipelineAnalysisError = null;

        // 1. Dynamic Routing Pipeline: Context -> Requirement -> Constraint Filter -> Tier-First Ranking
        try {
            routingContext = routingContextFactory.create(
                    requestBuilder.build(),
                    activeAgent,
                    "UNKNOWN",
                    explicitModel,
                    explicitModel ? config.getModel() : null
            );
            if (routingContext != null) {
                var reqOpt = requirementService.tryAnalyze(routingContext);
                if (reqOpt.isPresent()) {
                    routingRequirement = reqOpt.get();
                    modelFilterResult = constraintFilteringService.filter(routingRequirement);
                    if (modelFilterResult != null && modelFilterResult.hasAcceptedModels()) {
                        modelRankingResult = dynamicRankingService.rank(routingRequirement, modelFilterResult);

                        if (modelRankingResult.topCandidate().isPresent()) {
                            var top = modelRankingResult.topCandidate().get();
                            shadowRecommendedModel = top.model().modelName();
                            shadowTopScore = top.totalScore();
                        }

                        var rankingSummary = modelRankingResult.rankedCandidates().stream()
                                .map(cs -> String.format("%s:%.2f", cs.model().modelName(), cs.totalScore()))
                                .toList();

                        log.debug("Dynamic Model Ranking [invocationId={}]: taskType={}, reason={}, recommended={}, topScore={}, ranking={}",
                                context.invocationId(), routingRequirement.taskType(),
                                modelRankingResult.selectionReason(), shadowRecommendedModel, shadowTopScore, rankingSummary);
                    }
                }
            }
        } catch (Exception e) {
            pipelineAnalysisError = e;
            log.warn("Dynamic routing pipeline analysis encountered exception for invocation [{}]: {}", context.invocationId(), e.getMessage());
        }

        // 2. Production Model Selection
        String actualModel;
        SelectionSource selectionSource;

        if (explicitModel) {
            // =========================================================================
            // Explicit User Model Selection: Must validate and NEVER silently auto-switch
            // =========================================================================
            String customModel = config.getModel().trim();
            Optional<ModelProfile> profileOpt = modelCatalogService.findByModelName(customModel)
                    .or(() -> modelCatalogService.findById(customModel));

            if (profileOpt.isPresent()) {
                ModelProfile profile = profileOpt.get();
                if (!profile.enabled()) {
                    throw new IllegalStateException(String.format("Explicit model [%s] is disabled in catalog", customModel));
                }

                // If routing requirement analysis failed or was empty, fail closed
                if (routingRequirement == null) {
                    String detail = pipelineAnalysisError != null ? ": " + pipelineAnalysisError.getMessage() : "";
                    throw new IllegalStateException(String.format("Validation unavailable for cataloged explicit model [%s]: routing analysis failed or returned empty%s", customModel, detail), pipelineAnalysisError);
                }

                ModelFilterResult filterRes;
                try {
                    filterRes = constraintFilteringService.filter(routingRequirement, List.of(profile));
                } catch (Exception e) {
                    throw new IllegalStateException(String.format("Validation unavailable for cataloged explicit model [%s]: filter analysis failed: %s", customModel, e.getMessage()), e);
                }

                if (filterRes == null || !filterRes.hasAcceptedModels()) {
                    List<ConstraintViolation> violations = (filterRes != null && !filterRes.rejected().isEmpty())
                            ? filterRes.rejected().get(0).violations()
                            : List.of();
                    throw new IllegalStateException(String.format("Explicit model [%s] violates request hard constraints: %s", customModel, violations));
                }

                ModelProviderProperties.ProviderConfig provConfig = providerRegistryService != null ? providerRegistryService.findProviderConfig(profile.modelName()) : null;
                boolean hasCustomTuple = StringUtils.isNotBlank(config.getBaseUrl()) && StringUtils.isNotBlank(config.getApiKey());
                if (provConfig == null && !hasCustomTuple) {
                    throw new IllegalStateException(String.format("Explicit model [%s] has no executable provider registered and no complete custom connection tuple provided", customModel));
                }
                actualModel = profile.modelName();
            } else {
                // Uncataloged explicit model: valid ONLY if executable via existing provider mapping or complete user tuple
                ModelProviderProperties.ProviderConfig provConfig = providerRegistryService != null ? providerRegistryService.findProviderConfig(customModel) : null;
                boolean hasCustomTuple = StringUtils.isNotBlank(config.getBaseUrl()) && StringUtils.isNotBlank(config.getApiKey());
                if (provConfig == null && !hasCustomTuple) {
                    throw new IllegalStateException(String.format("Uncataloged explicit model [%s] cannot be executed: no matching provider or complete custom connection tuple provided", customModel));
                }
                log.warn("Explicit model [{}] is not registered in catalog; capability metadata could not be verified", customModel);
                actualModel = customModel;
            }

            selectionSource = SelectionSource.USER_EXPLICIT;
            requestBuilder.model(actualModel);
            monitorService.modelRouted(
                    context.invocationId(),
                    activeAgent,
                    actualModel,
                    "USER_EXPLICIT",
                    0,
                    true,
                    "用户在工作台显式指定覆盖模型：" + actualModel,
                    Map.of("explicitModel", actualModel),
                    List.of(),
                    List.of(Map.of("tier", "Explicit User Selection", "status", "OVERRIDDEN", "detail", "用户前端直接选定模型"))
            );
            log.info("Explicit Model Selected invocationId={} model={}", context.invocationId(), actualModel);

        } else if (dynamicRoutingEnabled) {
            // =========================================================================
            // Production Dynamic Model Selection (Cheapest-Sufficient Router)
            // =========================================================================
            String dynamicModel = null;
            String dynamicReason = null;
            int complexity = 2;

            try {
                if (modelRankingResult != null && modelRankingResult.topCandidate().isPresent()) {
                    var top = modelRankingResult.topCandidate().get();
                    dynamicModel = top.model().modelName();
                    dynamicReason = modelRankingResult.selectionReason();
                    complexity = resolveComplexity(top.model().tier());
                } else {
                    log.warn("Dynamic ranking produced no candidate for invocation [{}], falling back to legacy router", context.invocationId());
                }
            } catch (Exception e) {
                log.warn("Dynamic model selection failed for invocation [{}], falling back to legacy router: {}", context.invocationId(), e.getMessage(), e);
            }

            if (dynamicModel != null) {
                actualModel = dynamicModel;
                selectionSource = SelectionSource.DYNAMIC_ROUTER;
                requestBuilder.model(actualModel);
                monitorService.modelRouted(
                        context.invocationId(),
                        activeAgent,
                        actualModel,
                        dynamicReason != null ? dynamicReason : "DYNAMIC_ROUTER",
                        complexity,
                        false,
                        "动态路由选定模型：" + actualModel + " (" + dynamicReason + ")",
                        Map.of("dynamicReason", dynamicReason != null ? dynamicReason : "", "topScore", shadowTopScore != null ? shadowTopScore : 0.0),
                        List.of(),
                        List.of(Map.of("tier", "Dynamic Router", "status", "SELECTED", "detail", dynamicReason != null ? dynamicReason : ""))
                );
                log.info("Dynamic Model Router invocationId={} model={} reason={} complexity={}", context.invocationId(), actualModel, dynamicReason, complexity);
            } else {
                // Bounded fallback to legacy router on internal failure or empty candidates
                ModelRoutingService.Decision decision = modelRoutingService.route(requestBuilder.build());
                actualModel = decision.model() != null ? decision.model() : defaultModel;
                selectionSource = SelectionSource.LEGACY_ROUTER;

                if (decision.model() != null) {
                    requestBuilder.model(decision.model());
                }
                monitorService.modelRouted(
                        context.invocationId(),
                        activeAgent,
                        actualModel,
                        decision.reason(),
                        decision.complexity(),
                        false,
                        decision.narrative(),
                        decision.metrics(),
                        decision.matchedKeywords(),
                        decision.pipelineTrail()
                );
                log.info("Legacy Model Router (Fallback) invocationId={} model={} reason={} complexity={}", context.invocationId(), actualModel, decision.reason(), decision.complexity());
            }

        } else {
            // =========================================================================
            // Dynamic Routing Disabled: Pure Legacy Router Path
            // =========================================================================
            ModelRoutingService.Decision decision = modelRoutingService.route(requestBuilder.build());
            actualModel = decision.model() != null ? decision.model() : defaultModel;
            selectionSource = SelectionSource.LEGACY_ROUTER;

            if (decision.model() != null) {
                requestBuilder.model(decision.model());
            }
            monitorService.modelRouted(
                    context.invocationId(),
                    activeAgent,
                    actualModel,
                    decision.reason(),
                    decision.complexity(),
                    false,
                    decision.narrative(),
                    decision.metrics(),
                    decision.matchedKeywords(),
                    decision.pipelineTrail()
            );
            log.info("Legacy Model Router invocationId={} model={} reason={} complexity={}", context.invocationId(), actualModel, decision.reason(), decision.complexity());
        }

        // 3. Record unified Routing Comparison & Structured Evaluation Telemetry
        Boolean matched = (shadowRecommendedModel != null && actualModel != null)
                ? shadowRecommendedModel.equalsIgnoreCase(actualModel)
                : null;

        var comparison = new RoutingShadowComparison(
                actualModel,
                shadowRecommendedModel,
                matched,
                shadowTopScore,
                selectionSource
        );

        log.debug("Routing Comparison [invocationId={}]: actualModel={}, recommendedModel={}, matched={}, recommendedScore={}, actualSource={}",
                context.invocationId(), comparison.actualModel(), comparison.recommendedModel(), comparison.matched(), comparison.recommendedScore(), comparison.actualSource());

        try {
            if (evaluationService != null) {
                var evalRecord = evaluationService.buildRecord(
                        context.invocationId(),
                        routingContext,
                        routingRequirement,
                        modelFilterResult,
                        modelRankingResult,
                        comparison
                );
                evaluationService.tryRecord(evalRecord);
            }
        } catch (Exception e) {
            log.warn("Evaluation recording skipped due to exception: {}", e.getMessage());
        }

        // 4. Match Provider tuple for actualModel (BaseUrl, ApiKey, CompletionsPath)
        ModelProviderProperties.ProviderConfig providerConfig = providerRegistryService != null ? providerRegistryService.findProviderConfig(actualModel) : null;

        GenerateContentConfig.Builder configBuilder = requestBuilder.config().isPresent() ?
                requestBuilder.config().get().toBuilder() : GenerateContentConfig.builder();

        HttpOptions.Builder httpOptionsBuilder = configBuilder.build().httpOptions().isPresent() ?
                configBuilder.build().httpOptions().get().toBuilder() : HttpOptions.builder();

        Map<String, String> headers = new HashMap<>();
        if (httpOptionsBuilder.build().headers().isPresent()) {
            headers.putAll(httpOptionsBuilder.build().headers().get());
        }

        // 4.1. Apply attributes from Provider Registry
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

        // 4.2. Apply explicit user custom connection tuple (highest priority override)
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

    private int resolveComplexity(ModelTier tier) {
        if (tier == null) return 2;
        return switch (tier) {
            case FAST -> 1;
            case BALANCED -> 2;
            case REASONING -> 3;
        };
    }
}
