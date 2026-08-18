package cn.bugstack.ai.domain.agent.service.llm.provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多厂商模型提供商配置属性
 * 为每一个 Provider / Model Pattern 绑定独立解耦的 BaseUrl, ApiKey, CompletionsPath 三元组
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent")
public class ModelProviderProperties {

    private Map<String, ProviderConfig> modelProviders = new LinkedHashMap<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderConfig {
        /** 正则表达式匹配模型名称，如 ^(qwen|qwen3).* */
        private String pattern;
        private String baseUrl;
        private String apiKey;
        private String completionsPath;
    }
}
