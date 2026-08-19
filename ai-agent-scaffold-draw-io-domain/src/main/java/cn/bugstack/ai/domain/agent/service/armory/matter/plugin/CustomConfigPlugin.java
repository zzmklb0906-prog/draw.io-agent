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

    public CustomConfigPlugin(ModelRoutingService modelRoutingService,
                              LightweightMonitorService monitorService,
                              ModelProviderRegistryService providerRegistryService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory routingContextFactory,
                              cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService requirementService,
                              cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService constraintFilteringService) {
        super("CustomConfigPlugin");
        this.modelRoutingService = modelRoutingService;
        this.monitorService = monitorService;
        this.providerRegistryService = providerRegistryService;
        this.routingContextFactory = routingContextFactory;
        this.requirementService = requirementService;
        this.constraintFilteringService = constraintFilteringService;
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder requestBuilder) {
        String sessionId = context.sessionId();
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.getConfig(sessionId);

        boolean explicitModel = config != null && config.isCustomModelSelected() && StringUtils.isNotBlank(config.getModel());
        String finalModel = requestBuilder.build().model().orElse("");
        String activeAgent = monitorService.activeAgentName(context.invocationId());

        // Shadow Mode: Phase 3 Requirement & Phase 4 Hard Constraint Filter (observation only, does NOT alter model selection)
        try {
            var routingContext = routingContextFactory.create(
                    requestBuilder.build(),
                    activeAgent,
                    "UNKNOWN",
                    explicitModel,
                    explicitModel ? config.getModel() : null
            );
            requirementService.tryAnalyze(routingContext).ifPresent(requirement -> {
                var filterResult = constraintFilteringService.filter(requirement);
                var acceptedNames = filterResult.accepted().stream().map(m -> m.modelName()).toList();
                var rejectedSummary = filterResult.rejected().stream()
                        .map(r -> r.model().modelName() + ":" + r.violations().stream().map(v -> v.reason().name()).toList())
                        .toList();

                log.debug("Shadow Hard Constraint Filter [invocationId={}]: taskType={}, accepted={}, rejected={}",
                        context.invocationId(), requirement.taskType(), acceptedNames, rejectedSummary);
            });
        } catch (Exception e) {
            log.warn("Shadow constraint filtering skipped due to exception: {}", e.getMessage());
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
