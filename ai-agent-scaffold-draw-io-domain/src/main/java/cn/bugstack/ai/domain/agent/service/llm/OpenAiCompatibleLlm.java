package cn.bugstack.ai.domain.agent.service.llm;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.chat.ChatCompletionsClient;
import com.google.adk.models.chat.ChatCompletionsHttpClient;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADK BaseLlm for OpenAI-compatible providers such as DeepSeek.
 *
 * <p>The model layer is independent from Spring AI. Per-session overrides are
 * read from request HttpOptions populated by CustomConfigPlugin, so the
 * frontend's model settings remain effective without mutating shared agents.</p>
 */
public final class OpenAiCompatibleLlm extends BaseLlm {

    private static final String BASE_URL = "X-Custom-Base-Url";
    private static final String API_KEY = "X-Custom-Api-Key";
    private static final String COMPLETIONS_PATH = "X-Custom-Completions-Path";

    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultCompletionsPath;
    /**
     * Only clients created from server-owned defaults are shared. A request supplied API key
     * must never become part of a long-lived cache key or a cached HTTP client.
     */
    private final ConcurrentHashMap<DefaultClientKey, ChatCompletionsClient> delegates = new ConcurrentHashMap<>();

    public OpenAiCompatibleLlm(String model, String baseUrl, String apiKey, String completionsPath) {
        super(model);
        this.defaultBaseUrl = baseUrl;
        this.defaultApiKey = apiKey;
        this.defaultCompletionsPath = completionsPath;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        return delegate(request).complete(request, stream);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("OpenAI Chat Completions does not provide ADK live connections");
    }

    private ChatCompletionsClient delegate(LlmRequest request) {
        Map<String, String> headers = request.config()
                .flatMap(GenerateContentConfig::httpOptions)
                .flatMap(HttpOptions::headers)
                .orElse(Map.of());
        String suppliedBaseUrl = headers.get(BASE_URL);
        String suppliedApiKey = headers.get(API_KEY);
        String suppliedPath = headers.get(COMPLETIONS_PATH);
        String baseUrl = valueOrDefault(suppliedBaseUrl, defaultBaseUrl);
        String apiKey = valueOrDefault(suppliedApiKey, defaultApiKey);
        String path = valueOrDefault(suppliedPath, defaultCompletionsPath);
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl, path);

        // Per-request credentials are deliberately ephemeral. Even if their value happens to
        // equal the configured default, their presence means the caller owns their lifecycle.
        if (suppliedApiKey != null && !suppliedApiKey.isBlank()) {
            return createDelegate(normalizedBaseUrl, apiKey);
        }
        DefaultClientKey key = new DefaultClientKey(normalizedBaseUrl);
        return delegates.computeIfAbsent(key, ignored -> createDelegate(normalizedBaseUrl, defaultApiKey));
    }

    private ChatCompletionsClient createDelegate(String baseUrl, String apiKey) {
        HttpOptions options = HttpOptions.builder()
                .baseUrl(baseUrl)
                .headers(Map.of("Authorization", "Bearer " + apiKey))
                .timeout(600_000)
                .build();
        return new ChatCompletionsHttpClient(options);
    }

    public static String normalizeBaseUrl(String baseUrl, String completionsPath) {
        String normalizedBase = valueOrDefault(baseUrl, "https://api.deepseek.com").replaceAll("/+$", "");
        if (completionsPath == null || completionsPath.isBlank()) return normalizedBase;
        String path = completionsPath.replaceAll("^/+|/+$", "");
        int suffix = path.lastIndexOf("/chat/completions");
        if (suffix <= 0) return normalizedBase;
        String prefix = path.substring(0, suffix);
        return normalizedBase.endsWith("/" + prefix) ? normalizedBase : normalizedBase + "/" + prefix;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record DefaultClientKey(String baseUrl) { }
}
