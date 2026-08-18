package cn.bugstack.ai.domain.agent.service.llm.provider;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 模型提供商注册与装配中心
 * 根据模型名称自动匹配对应的 BaseUrl, ApiKey, CompletionsPath 三元组
 */
@Slf4j
@Service
public class ModelProviderRegistryService {

    private final ModelProviderProperties properties;

    public ModelProviderRegistryService(ModelProviderProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据目标模型名称查找匹配的厂商配置
     */
    public ModelProviderProperties.ProviderConfig findProviderConfig(String modelName) {
        if (StringUtils.isBlank(modelName) || properties.getModelProviders() == null) {
            return null;
        }

        for (Map.Entry<String, ModelProviderProperties.ProviderConfig> entry : properties.getModelProviders().entrySet()) {
            ModelProviderProperties.ProviderConfig config = entry.getValue();
            if (config != null && StringUtils.isNotBlank(config.getPattern())) {
                try {
                    if (Pattern.compile(config.getPattern(), Pattern.CASE_INSENSITIVE).matcher(modelName).matches()) {
                        log.debug("Found matching provider [{}] for model [{}]", entry.getKey(), modelName);
                        return config;
                    }
                } catch (Exception e) {
                    log.warn("Invalid regex pattern for provider [{}]: {}", entry.getKey(), config.getPattern());
                }
            }
        }

        log.debug("No custom provider matched for model [{}], using fallback defaults", modelName);
        return null;
    }
}
