package cn.bugstack.ai.domain.agent.service.llm.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Configuration Properties binding for Model Catalog.
 *
 * <p>Binds structured YAML configurations from {@code ai.agent.model-catalog.models}.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.model-catalog")
public class ModelCatalogProperties {

    private List<ModelConfig> models = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelConfig {
        private String id;
        private String provider;
        private String modelName;
        @Builder.Default
        private boolean enabled = true;
        private CapabilitiesConfig capabilities;
        private FeaturesConfig features;
        private LimitsConfig limits;
        private PricingConfig pricing;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapabilitiesConfig {
        private int reasoning;
        private int instructionFollowing;
        private int coding;
        private int structuredOutput;
        private int toolCalling;
        private int vision;
        private int longContext;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeaturesConfig {
        private String toolCalling;
        private String structuredOutput;
        private String vision;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitsConfig {
        private long contextWindowTokens;
        private long maxOutputTokens;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PricingConfig {
        private BigDecimal inputPerMillionTokens;
        private BigDecimal outputPerMillionTokens;
        @Builder.Default
        private String currency = "CNY";
    }
}
