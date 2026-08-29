package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelCatalogService}.
 *
 * <p>Validates loading, querying, fail-fast boundary rules, and immutability.</p>
 */
class ModelCatalogServiceTest {

    // =========================================================================
    // Case 1 & 2 & 3: Normal loading, getAllModels, getEnabledModels
    // =========================================================================

    @Test
    void case1_2_3_loadsNormalCatalog_andFiltersEnabled() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setModels(List.of(
                createValidModel("qwen-fast", "qwen", "qwen3.7-flash", true),
                createValidModel("qwen-reasoning", "qwen", "qwen3.8-max", false)
        ));

        ModelCatalogService service = new ModelCatalogService(props);

        // Case 2: getAllModels
        assertEquals(2, service.getAllModels().size());

        // Case 3: getEnabledModels
        List<ModelProfile> enabled = service.getEnabledModels();
        assertEquals(1, enabled.size());
        assertEquals("qwen-fast", enabled.get(0).id());

        // Immutability check
        assertThrows(UnsupportedOperationException.class, () -> service.getAllModels().add(null));
        assertThrows(UnsupportedOperationException.class, () -> service.getEnabledModels().add(null));
    }

    // =========================================================================
    // Case 4 & 5: findById and findByModelName
    // =========================================================================

    @Test
    void case4_5_findById_and_findByModelName() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setModels(List.of(
                createValidModel("qwen3.7-flash", "qwen", "qwen3.7-flash", true)
        ));

        ModelCatalogService service = new ModelCatalogService(props);

        // Case 4: findById (case-insensitive)
        Optional<ModelProfile> byId = service.findById("QWEN3.7-FLASH");
        assertTrue(byId.isPresent());
        assertEquals("qwen3.7-flash", byId.get().id());

        // Case 5: findByModelName (case-insensitive)
        Optional<ModelProfile> byName = service.findByModelName("Qwen3.7-Flash");
        assertTrue(byName.isPresent());
        assertEquals("qwen", byName.get().provider());

        // Non-existent
        assertTrue(service.findById("not-exist").isEmpty());
        assertTrue(service.findByModelName("not-exist").isEmpty());
        assertTrue(service.findById(null).isEmpty());
        assertTrue(service.findByModelName("").isEmpty());
    }

    // =========================================================================
    // Case 6 & 7: Duplicate id and duplicate modelName
    // =========================================================================

    @Test
    void case6_duplicateId_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setModels(List.of(
                createValidModel("duplicate-id", "qwen", "model-a", true),
                createValidModel("DUPLICATE-ID", "qwen", "model-b", true)
        ));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("duplicate id"), "Must fail on duplicate id: " + ex.getMessage());
    }

    @Test
    void case7_duplicateModelName_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setModels(List.of(
                createValidModel("id-1", "qwen", "same-model-name", true),
                createValidModel("id-2", "qwen", "SAME-MODEL-NAME", true)
        ));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("duplicate modelName"), "Must fail on duplicate modelName: " + ex.getMessage());
    }

    // =========================================================================
    // Case 8 & 9: Blank id and blank provider
    // =========================================================================

    @Test
    void case8_blankId_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setModels(List.of(createValidModel("  ", "qwen", "model-a", true)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("id must not be blank"));
    }

    @Test
    void case9_blankProvider_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setModels(List.of(createValidModel("valid-id", "", "model-a", true)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("provider must not be blank"));
    }

    // =========================================================================
    // Fail-fast on missing capabilities config
    // =========================================================================

    @Test
    void missingCapabilities_shouldFailFast() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig config = createValidModel("id-no-cap", "qwen", "model-no-cap", true);
        config.setCapabilities(null);
        props.setModels(List.of(config));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("capabilities must be provided"), "Must fail-fast on null capabilities");
    }

    // =========================================================================
    // Case 10 & 11: Capability scores boundary checks (<0 or >100)
    // =========================================================================

    @Test
    void case10_capabilityLessThanZero_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig config = createValidModel("id-cap-neg", "qwen", "model-cap-neg", true);
        config.getCapabilities().setReasoning(-1);
        props.setModels(List.of(config));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("between 0 and 100"));
    }

    @Test
    void case11_capabilityGreaterThanHundred_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig config = createValidModel("id-cap-high", "qwen", "model-cap-high", true);
        config.getCapabilities().setStructuredOutput(101);
        props.setModels(List.of(config));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("between 0 and 100"));
    }

    // =========================================================================
    // Case 12 & 13: Limits boundary checks (contextWindowTokens <= 0, maxOutputTokens <= 0)
    // =========================================================================

    @Test
    void case12_contextWindowLessThanOrEqualToZero_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig config = createValidModel("id-cw-zero", "qwen", "model-cw-zero", true);
        config.getLimits().setContextWindowTokens(0);
        props.setModels(List.of(config));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("contextWindowTokens must be greater than 0"));
    }

    @Test
    void case13_maxOutputTokensLessThanOrEqualToZero_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig config = createValidModel("id-out-zero", "qwen", "model-out-zero", true);
        config.getLimits().setMaxOutputTokens(-5);
        props.setModels(List.of(config));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("maxOutputTokens must be greater than 0"));
    }

    // =========================================================================
    // Case 14 & 15: Pricing boundary checks (negative pricing, unknown/omitted)
    // =========================================================================

    @Test
    void case14_negativePrice_shouldFail() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig config = createValidModel("id-neg-price", "qwen", "model-neg-price", true);
        config.getPricing().setInputPerMillionTokens(BigDecimal.valueOf(-0.01));
        props.setModels(List.of(config));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("must be non-negative"));
    }

    @Test
    void case15_omittedPricing_shouldAllowAsUnknown() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig config = createValidModel("id-no-price", "qwen", "model-no-price", true);
        config.setPricing(null); // pricing omitted
        props.setModels(List.of(config));

        ModelCatalogService service = new ModelCatalogService(props);
        Optional<ModelProfile> profile = service.findById("id-no-price");
        assertTrue(profile.isPresent());
        assertNull(profile.get().pricing().inputPerMillionTokens());
        assertNull(profile.get().pricing().outputPerMillionTokens());
    }

    // =========================================================================
    // Case 16: Unknown features fallback to SupportStatus.UNKNOWN
    // =========================================================================

    @Test
    void case16_unknownFeatureString_standardizesToUnknown() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig config = createValidModel("id-unknown-feat", "qwen", "model-unknown-feat", true);
        config.setFeatures(new ModelCatalogProperties.FeaturesConfig("INVALID_STATUS", null, "   "));
        props.setModels(List.of(config));

        ModelCatalogService service = new ModelCatalogService(props);
        Optional<ModelProfile> profile = service.findById("id-unknown-feat");
        assertTrue(profile.isPresent());
        assertEquals(SupportStatus.UNKNOWN, profile.get().features().toolCalling());
        assertEquals(SupportStatus.UNKNOWN, profile.get().features().structuredOutput());
        assertEquals(SupportStatus.UNKNOWN, profile.get().features().vision());
    }

    // =========================================================================
    // ModelTier Configuration Tests
    // =========================================================================

    @Test
    void modelTier_explicitTierParsedCorrectly() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig fast = createValidModel("fast-model", "qwen", "fast-model", true);
        fast.setTier("FAST");
        ModelCatalogProperties.ModelConfig reasoning = createValidModel("reasoning-model", "qwen", "reasoning-model", true);
        reasoning.setTier("REASONING");
        props.setModels(List.of(fast, reasoning));

        ModelCatalogService service = new ModelCatalogService(props);
        assertEquals(ModelTier.FAST, service.findById("fast-model").orElseThrow().tier());
        assertEquals(ModelTier.REASONING, service.findById("reasoning-model").orElseThrow().tier());
    }

    @Test
    void modelTier_rejectsBlankTier() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig model = createValidModel("blank-tier-model", "qwen", "blank-tier-model", true);
        model.setTier(null);
        props.setModels(List.of(model));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("tier must not be blank"));

        model.setTier("   ");
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex2.getMessage().contains("tier must not be blank"));
    }

    @Test
    void modelTier_invalidTierThrowsIllegalArgumentException() {
        ModelCatalogProperties props = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig model = createValidModel("invalid-tier-model", "qwen", "invalid-tier-model", true);
        model.setTier("SUPER_FAST");
        props.setModels(List.of(model));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ModelCatalogService(props));
        assertTrue(ex.getMessage().contains("unknown tier [SUPER_FAST]"));
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private ModelCatalogProperties.ModelConfig createValidModel(String id, String provider, String modelName, boolean enabled) {
        return ModelCatalogProperties.ModelConfig.builder()
                .id(id)
                .provider(provider)
                .modelName(modelName)
                .tier("BALANCED")
                .enabled(enabled)
                .capabilities(ModelCatalogProperties.CapabilitiesConfig.builder()
                        .reasoning(80)
                        .instructionFollowing(80)
                        .coding(80)
                        .structuredOutput(80)
                        .toolCalling(80)
                        .vision(0)
                        .longContext(80)
                        .build())
                .features(ModelCatalogProperties.FeaturesConfig.builder()
                        .toolCalling("SUPPORTED")
                        .structuredOutput("SUPPORTED")
                        .vision("UNSUPPORTED")
                        .build())
                .limits(ModelCatalogProperties.LimitsConfig.builder()
                        .contextWindowTokens(131072)
                        .maxOutputTokens(8192)
                        .build())
                .pricing(ModelCatalogProperties.PricingConfig.builder()
                        .inputPerMillionTokens(BigDecimal.valueOf(1.0))
                        .outputPerMillionTokens(BigDecimal.valueOf(2.0))
                        .currency("CNY")
                        .build())
                .build();
    }
}
