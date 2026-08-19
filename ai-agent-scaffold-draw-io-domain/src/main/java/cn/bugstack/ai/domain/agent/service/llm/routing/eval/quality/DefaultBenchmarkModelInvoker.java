package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import cn.bugstack.ai.domain.agent.service.llm.OpenAiCompatibleLlm;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderProperties;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderRegistryService;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation of {@link BenchmarkModelInvoker} leveraging {@link ModelProviderRegistryService} and {@link OpenAiCompatibleLlm}.
 */
@Slf4j
@Component
public class DefaultBenchmarkModelInvoker implements BenchmarkModelInvoker {

    private final ModelProviderRegistryService providerRegistryService;
    private final BenchmarkExecutionProperties properties;

    public DefaultBenchmarkModelInvoker(ModelProviderRegistryService providerRegistryService,
                                        BenchmarkExecutionProperties properties) {
        this.providerRegistryService = providerRegistryService;
        this.properties = properties != null ? properties : new BenchmarkExecutionProperties();
    }

    @Override
    public BenchmarkRawResponse invoke(String modelName, BenchmarkCase benchmarkCase) {
        if (StringUtils.isBlank(modelName) || benchmarkCase == null) {
            return BenchmarkRawResponse.failure("INVALID_INPUT", "Model name or BenchmarkCase is null/blank", 0L);
        }

        ModelProviderProperties.ProviderConfig providerConfig = providerRegistryService.findProviderConfig(modelName);
        if (providerConfig == null) {
            return BenchmarkRawResponse.failure("PROVIDER_NOT_CONFIGURED", "No provider configuration found for model [" + modelName + "]", 0L);
        }

        Map<String, String> headers = new HashMap<>();
        if (StringUtils.isNotBlank(providerConfig.getBaseUrl())) {
            headers.put("X-Custom-Base-Url", providerConfig.getBaseUrl());
        }
        if (StringUtils.isNotBlank(providerConfig.getApiKey())) {
            headers.put("X-Custom-Api-Key", providerConfig.getApiKey());
        }
        if (StringUtils.isNotBlank(providerConfig.getCompletionsPath())) {
            headers.put("X-Custom-Completions-Path", providerConfig.getCompletionsPath());
        }

        int timeout = properties.getRequestTimeoutSeconds() > 0 ? properties.getRequestTimeoutSeconds() * 1000 : 60_000;
        HttpOptions httpOptions = HttpOptions.builder()
                .headers(headers)
                .timeout(timeout)
                .build();

        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature(0.0f) // Deterministic output for benchmarking
                .httpOptions(httpOptions)
                .build();

        LlmRequest request = LlmRequest.builder()
                .model(modelName)
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText(benchmarkCase.prompt()))).build()))
                .config(config)
                .build();

        OpenAiCompatibleLlm llm = new OpenAiCompatibleLlm(
                modelName,
                providerConfig.getBaseUrl(),
                providerConfig.getApiKey(),
                providerConfig.getCompletionsPath()
        );

        long start = System.currentTimeMillis();
        try {
            LlmResponse response = llm.generateContent(request, false)
                    .timeout(timeout, TimeUnit.MILLISECONDS)
                    .blockingFirst();

            long elapsed = System.currentTimeMillis() - start;
            String text = "";
            Long promptTokens = null;
            Long completionTokens = null;
            Long totalTokens = null;

            if (response != null) {
                if (response.content().isPresent()) {
                    Content c = response.content().get();
                    if (c.parts().isPresent()) {
                        StringBuilder sb = new StringBuilder();
                        for (Part p : c.parts().get()) {
                            p.text().ifPresent(sb::append);
                        }
                        text = sb.toString();
                    }
                }
                if (response.usageMetadata().isPresent()) {
                    var usage = response.usageMetadata().get();
                    promptTokens = usage.promptTokenCount().map(Integer::longValue).orElse(null);
                    completionTokens = usage.candidatesTokenCount().map(Integer::longValue).orElse(null);
                    totalTokens = usage.totalTokenCount().map(Integer::longValue).orElse(null);
                }
            }

            return BenchmarkRawResponse.success(text, elapsed, promptTokens, completionTokens, totalTokens);
        } catch (Exception e) {

            long elapsed = System.currentTimeMillis() - start;
            String errType = e.getClass().getSimpleName();
            log.warn("Benchmark invocation failed for model [{}] on case [{}]: {}", modelName, benchmarkCase.caseId(), e.getMessage());
            return BenchmarkRawResponse.failure(errType, e.getMessage(), elapsed);
        }
    }
}
