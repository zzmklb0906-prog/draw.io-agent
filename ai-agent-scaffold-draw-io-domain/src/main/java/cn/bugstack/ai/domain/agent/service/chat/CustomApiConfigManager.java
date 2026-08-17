package cn.bugstack.ai.domain.agent.service.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义 API 配置管理器
 */
public class CustomApiConfigManager {

    private static final Map<String, CustomApiConfig> configMap = new ConcurrentHashMap<>();

    public static void setConfig(String sessionId, CustomApiConfig config) {
        if (sessionId != null && config != null) {
            configMap.put(sessionId, config);
        }
    }

    public static CustomApiConfig getConfig(String sessionId) {
        if (sessionId == null) return null;
        return configMap.get(sessionId);
    }

    public static void clearConfig(String sessionId) {
        if (sessionId != null) {
            configMap.remove(sessionId);
        }
    }

    public static class CustomApiConfig {
        private String baseUrl;
        private String apiKey;
        private String completionsPath;
        private String model;
        /** 是否为用户主动选择的自定义模型（区别于默认模型） */
        private boolean customModelSelected;

        public String getBaseUrl() { return baseUrl; }
        public String getApiKey() { return apiKey; }
        public String getCompletionsPath() { return completionsPath; }
        public String getModel() { return model; }
        public boolean isCustomModelSelected() { return customModelSelected; }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String baseUrl;
            private String apiKey;
            private String completionsPath;
            private String model;
            private boolean customModelSelected;

            public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
            public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
            public Builder completionsPath(String completionsPath) { this.completionsPath = completionsPath; return this; }
            public Builder model(String model) { this.model = model; return this; }
            public Builder customModelSelected(boolean customModelSelected) { this.customModelSelected = customModelSelected; return this; }

            public CustomApiConfig build() {
                CustomApiConfig config = new CustomApiConfig();
                config.baseUrl = this.baseUrl;
                config.apiKey = this.apiKey;
                config.completionsPath = this.completionsPath;
                config.model = this.model;
                config.customModelSelected = this.customModelSelected;
                return config;
            }
        }
    }
}
