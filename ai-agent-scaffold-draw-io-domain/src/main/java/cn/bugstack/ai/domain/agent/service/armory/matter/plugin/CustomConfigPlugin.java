package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.service.chat.CustomApiConfigManager;
import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
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
import java.util.Map;

@Slf4j
@Service("customConfigPlugin")
public class CustomConfigPlugin extends BasePlugin {
    private final ModelRoutingService modelRoutingService;
    private final LightweightMonitorService monitorService;

    public CustomConfigPlugin(ModelRoutingService modelRoutingService, LightweightMonitorService monitorService) {
        super("CustomConfigPlugin");
        this.modelRoutingService=modelRoutingService;
        this.monitorService=monitorService;
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder requestBuilder) {
        String sessionId = context.sessionId();
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.getConfig(sessionId);

        boolean explicitModel=config!=null&&config.isCustomModelSelected()&&StringUtils.isNotBlank(config.getModel());
        if(!explicitModel){
            ModelRoutingService.Decision decision=modelRoutingService.route(requestBuilder.build());
            String selected=decision.model()!=null?decision.model():requestBuilder.build().model().orElse("");
            if(decision.model()!=null)requestBuilder.model(decision.model());
            monitorService.modelRouted(context.invocationId(),monitorService.activeAgentName(context.invocationId()),selected,decision.reason(),decision.complexity(),false);
            log.info("Model Router invocationId={} model={} reason={} complexity={}",context.invocationId(),selected,decision.reason(),decision.complexity());
        }
        if (config != null) {
            GenerateContentConfig.Builder configBuilder;
            if (requestBuilder.config().isPresent()) {
                configBuilder = requestBuilder.config().get().toBuilder();
            } else {
                configBuilder = GenerateContentConfig.builder();
            }

            HttpOptions.Builder httpOptionsBuilder;
            if (configBuilder.build().httpOptions().isPresent()) {
                httpOptionsBuilder = configBuilder.build().httpOptions().get().toBuilder();
            } else {
                httpOptionsBuilder = HttpOptions.builder();
            }

            Map<String, String> headers = new HashMap<>();
            if (httpOptionsBuilder.build().headers().isPresent()) {
                headers.putAll(httpOptionsBuilder.build().headers().get());
            }

            // 判断是否有任何自定义配置（baseUrl、apiKey、completionsPath 中至少一个非空，或用户主动选了自定义模型）
            boolean hasCustomBaseUrl = StringUtils.isNotBlank(config.getBaseUrl());
            boolean hasCustomApiKey = StringUtils.isNotBlank(config.getApiKey());
            boolean hasCustomCompletionsPath = StringUtils.isNotBlank(config.getCompletionsPath());

            if (hasCustomBaseUrl) {
                headers.put("X-Custom-Base-Url", config.getBaseUrl());
            }
            if (hasCustomApiKey) {
                headers.put("X-Custom-Api-Key", config.getApiKey());
            }
            if (hasCustomCompletionsPath) {
                headers.put("X-Custom-Completions-Path", config.getCompletionsPath());
            }

            // 关键：只有当用户主动选择了自定义模型时才设置 requestBuilder.model()
            // config.isCustomModelSelected() 由 Controller 层根据前端传入的 customModel 字段是否非空来设置
            // 这样可以区分「用户选默认模型」和「用户选自定义模型」两种情况
            if (config.isCustomModelSelected() && StringUtils.isNotBlank(config.getModel())) {
                requestBuilder.model(config.getModel());
                monitorService.modelRouted(context.invocationId(), monitorService.activeAgentName(context.invocationId()), config.getModel(), "USER_EXPLICIT", 0, true);
                // 同时在 headers 中传递标记，供 MyMessageConverter 判断
                headers.put("X-Custom-Model-Selected", "true");
            }

            httpOptionsBuilder.headers(headers);
            configBuilder.httpOptions(httpOptionsBuilder.build());
            requestBuilder.config(configBuilder.build());
        }

        return super.beforeModelCallback(context, requestBuilder);
    }
}
