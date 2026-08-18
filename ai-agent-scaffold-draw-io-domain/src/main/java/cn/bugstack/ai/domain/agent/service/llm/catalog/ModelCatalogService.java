package cn.bugstack.ai.domain.agent.service.llm.catalog;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model Catalog Service — In-memory registry of immutable {@link ModelProfile}s.
 *
 * <p>Responsible for loading, validating, and querying model profiles defined in
 * configuration (e.g. {@code model-catalog.yml}).</p>
 *
 * <p><strong>Architectural Boundary:</strong>
 * This service is purely a read-only metadata catalog (answering WHAT capabilities and limits
 * a model possesses). It does NOT perform provider endpoint resolution (handled by
 * {@code ModelProviderRegistryService}) and does NOT make runtime model selection decisions
 * (handled by {@code ModelRoutingService}).</p>
 */
@Slf4j
@Service
public class ModelCatalogService {

    private final List<ModelProfile> allProfiles;
    private final List<ModelProfile> enabledProfiles;
    private final Map<String, ModelProfile> idIndex;
    private final Map<String, ModelProfile> modelNameIndex;

    public ModelCatalogService(ModelCatalogProperties properties) {
        List<ModelProfile> loaded = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenModelNames = new HashSet<>();
        Map<String, ModelProfile> byId = new LinkedHashMap<>();
        Map<String, ModelProfile> byModelName = new LinkedHashMap<>();

        if (properties != null && properties.getModels() != null) {
            for (ModelCatalogProperties.ModelConfig config : properties.getModels()) {
                ModelProfile profile = validateAndConvert(config, seenIds, seenModelNames);
                loaded.add(profile);
                byId.put(profile.id().toLowerCase(), profile);
                byModelName.put(profile.modelName().toLowerCase(), profile);
            }
        }

        this.allProfiles = List.copyOf(loaded);
        this.enabledProfiles = List.copyOf(loaded.stream().filter(ModelProfile::enabled).toList());
        this.idIndex = Collections.unmodifiableMap(byId);
        this.modelNameIndex = Collections.unmodifiableMap(byModelName);

        log.info("Model Catalog initialized with {} models ({} enabled)", allProfiles.size(), enabledProfiles.size());
    }

    /**
     * Returns an unmodifiable list of all registered model profiles.
     */
    public List<ModelProfile> getAllModels() {
        return allProfiles;
    }

    /**
     * Returns an unmodifiable list of all enabled model profiles.
     */
    public List<ModelProfile> getEnabledModels() {
        return enabledProfiles;
    }

    /**
     * Finds a model profile by its unique catalog ID (case-insensitive).
     */
    public Optional<ModelProfile> findById(String id) {
        if (StringUtils.isBlank(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(idIndex.get(id.trim().toLowerCase()));
    }

    /**
     * Finds a model profile by its actual API model name (case-insensitive).
     */
    public Optional<ModelProfile> findByModelName(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(modelNameIndex.get(modelName.trim().toLowerCase()));
    }

    // -------------------------------------------------------------------------
    // Validation & Conversion
    // -------------------------------------------------------------------------

    private ModelProfile validateAndConvert(ModelCatalogProperties.ModelConfig config,
                                            Set<String> seenIds,
                                            Set<String> seenModelNames) {
        if (config == null) {
            throw new IllegalArgumentException("Model catalog entry cannot be null");
        }

        String id = config.getId();
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Invalid model catalog entry: id must not be blank");
        }
        id = id.trim();
        if (!seenIds.add(id.toLowerCase())) {
            throw new IllegalArgumentException(String.format("Invalid model catalog entry [id=%s]: duplicate id", id));
        }

        String modelName = config.getModelName();
        if (StringUtils.isBlank(modelName)) {
            throw new IllegalArgumentException(String.format("Invalid model catalog entry [id=%s]: modelName must not be blank", id));
        }
        modelName = modelName.trim();
        if (!seenModelNames.add(modelName.toLowerCase())) {
            throw new IllegalArgumentException(String.format("Invalid model catalog entry [id=%s]: duplicate modelName [%s]", id, modelName));
        }

        String provider = config.getProvider();
        if (StringUtils.isBlank(provider)) {
            throw new IllegalArgumentException(String.format("Invalid model catalog entry [id=%s]: provider must not be blank", id));
        }
        provider = provider.trim().toLowerCase();

        ModelCapabilities capabilities = convertCapabilities(id, config.getCapabilities());
        ModelFeatures features = convertFeatures(config.getFeatures());
        ModelLimits limits = convertLimits(id, config.getLimits());
        ModelPricing pricing = convertPricing(id, config.getPricing());

        return new ModelProfile(
                id,
                provider,
                modelName,
                config.isEnabled(),
                capabilities,
                features,
                limits,
                pricing
        );
    }

    private ModelCapabilities convertCapabilities(String id, ModelCatalogProperties.CapabilitiesConfig config) {
        if (config == null) {
            throw new IllegalArgumentException(String.format("Invalid model catalog entry [id=%s]: capabilities must be provided", id));
        }
        validateScore(id, "reasoning", config.getReasoning());
        validateScore(id, "instructionFollowing", config.getInstructionFollowing());
        validateScore(id, "coding", config.getCoding());
        validateScore(id, "structuredOutput", config.getStructuredOutput());
        validateScore(id, "toolCalling", config.getToolCalling());
        validateScore(id, "vision", config.getVision());
        validateScore(id, "longContext", config.getLongContext());

        return new ModelCapabilities(
                config.getReasoning(),
                config.getInstructionFollowing(),
                config.getCoding(),
                config.getStructuredOutput(),
                config.getToolCalling(),
                config.getVision(),
                config.getLongContext()
        );
    }

    private void validateScore(String id, String scoreName, int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException(String.format(
                    "Invalid model catalog entry [id=%s]: %s score must be between 0 and 100, got %d",
                    id, scoreName, score));
        }
    }

    private ModelFeatures convertFeatures(ModelCatalogProperties.FeaturesConfig config) {
        if (config == null) {
            return ModelFeatures.defaultFeatures();
        }
        SupportStatus toolCalling = parseSupportStatus(config.getToolCalling());
        SupportStatus structuredOutput = parseSupportStatus(config.getStructuredOutput());
        SupportStatus vision = parseSupportStatus(config.getVision());
        return new ModelFeatures(toolCalling, structuredOutput, vision);
    }

    private SupportStatus parseSupportStatus(String statusStr) {
        if (StringUtils.isBlank(statusStr)) {
            return SupportStatus.UNKNOWN;
        }
        try {
            return SupportStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SupportStatus.UNKNOWN;
        }
    }

    private ModelLimits convertLimits(String id, ModelCatalogProperties.LimitsConfig config) {
        if (config == null) {
            throw new IllegalArgumentException(String.format("Invalid model catalog entry [id=%s]: limits must be provided", id));
        }
        if (config.getContextWindowTokens() <= 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid model catalog entry [id=%s]: contextWindowTokens must be greater than 0, got %d",
                    id, config.getContextWindowTokens()));
        }
        if (config.getMaxOutputTokens() <= 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid model catalog entry [id=%s]: maxOutputTokens must be greater than 0, got %d",
                    id, config.getMaxOutputTokens()));
        }
        return new ModelLimits(config.getContextWindowTokens(), config.getMaxOutputTokens());
    }

    private ModelPricing convertPricing(String id, ModelCatalogProperties.PricingConfig config) {
        if (config == null) {
            return ModelPricing.unknown();
        }
        BigDecimal inPrice = config.getInputPerMillionTokens();
        BigDecimal outPrice = config.getOutputPerMillionTokens();

        if (inPrice != null && inPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid model catalog entry [id=%s]: inputPerMillionTokens must be non-negative, got %s",
                    id, inPrice));
        }
        if (outPrice != null && outPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid model catalog entry [id=%s]: outputPerMillionTokens must be non-negative, got %s",
                    id, outPrice));
        }

        String currency = StringUtils.isNotBlank(config.getCurrency()) ? config.getCurrency().trim() : "CNY";
        return new ModelPricing(inPrice, outPrice, currency);
    }
}
