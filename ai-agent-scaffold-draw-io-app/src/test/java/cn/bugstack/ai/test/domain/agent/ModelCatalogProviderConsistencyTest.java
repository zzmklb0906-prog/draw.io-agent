package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogProperties;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.catalog.SupportStatus;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderProperties;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consistency and Factual Regression tests between Model Catalog (model-catalog.yml)
 * and Provider Registry (application-dev.yml) (Cases 17 - 21 + Factual Regression).
 *
 * <p>Validates:
 * <ul>
 *   <li>The three active routing model slots (fast, balanced, reasoning) exist and are enabled in Catalog.</li>
 *   <li>Factual regression: context window, max output, pricing, and features strictly match api-model.md.</li>
 *   <li>Every enabled model in Catalog has a corresponding provider configured in ProviderRegistry.</li>
 *   <li>Every enabled model in Catalog can be resolved by {@link ModelProviderRegistryService#findProviderConfig(String)}.</li>
 * </ul>
 * </p>
 */
class ModelCatalogProviderConsistencyTest {

    private ModelCatalogService catalogService;
    private ModelProviderRegistryService providerRegistryService;
    private ModelProviderProperties providerProperties;

    private String fastModel;
    private String balancedModel;
    private String reasoningModel;

    @BeforeEach
    void setUp() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        // 1. Load application-dev.yml
        List<PropertySource<?>> devSources = loader.load("application-dev.yml", new ClassPathResource("application-dev.yml"));
        for (PropertySource<?> source : devSources) {
            propertySources.addLast(source);
        }

        // 2. Load model-catalog.yml
        List<PropertySource<?>> catalogSources = loader.load("model-catalog.yml", new ClassPathResource("model-catalog.yml"));
        for (PropertySource<?> source : catalogSources) {
            propertySources.addLast(source);
        }

        ConfigurationPropertySources.attach(environment);
        Binder binder = Binder.get(environment);

        // Bind ModelCatalogProperties
        ModelCatalogProperties catalogProperties = binder.bind("ai.agent.model-catalog", ModelCatalogProperties.class)
                .orElseGet(ModelCatalogProperties::new);
        this.catalogService = new ModelCatalogService(catalogProperties);

        // Bind ModelProviderProperties
        this.providerProperties = binder.bind("ai.agent", ModelProviderProperties.class)
                .orElseGet(ModelProviderProperties::new);
        this.providerRegistryService = new ModelProviderRegistryService(this.providerProperties);

        this.fastModel = environment.getProperty("ai.agent.model-routing.fast-model", "qwen3.7-flash");
        this.balancedModel = environment.getProperty("ai.agent.model-routing.balanced-model", "qwen3.7-plus");
        this.reasoningModel = environment.getProperty("ai.agent.model-routing.reasoning-model", "qwen3.8-max");
    }

    // =========================================================================
    // Case 17, 18, 19: Current active routing slots exist and are enabled
    // =========================================================================

    @Test
    void case17_fastModel_existsAndEnabledInCatalog() {
        Optional<ModelProfile> profile = catalogService.findByModelName(fastModel);
        assertTrue(profile.isPresent(), "Fast model [" + fastModel + "] must exist in Model Catalog");
        assertTrue(profile.get().enabled(), "Fast model [" + fastModel + "] must be enabled");
    }

    @Test
    void case18_balancedModel_existsAndEnabledInCatalog() {
        Optional<ModelProfile> profile = catalogService.findByModelName(balancedModel);
        assertTrue(profile.isPresent(), "Balanced model [" + balancedModel + "] must exist in Model Catalog");
        assertTrue(profile.get().enabled(), "Balanced model [" + balancedModel + "] must be enabled");
    }

    @Test
    void case19_reasoningModel_existsAndEnabledInCatalog() {
        Optional<ModelProfile> profile = catalogService.findByModelName(reasoningModel);
        assertTrue(profile.isPresent(), "Reasoning model [" + reasoningModel + "] must exist in Model Catalog");
        assertTrue(profile.get().enabled(), "Reasoning model [" + reasoningModel + "] must be enabled");
    }

    // =========================================================================
    // Factual Regression: Lock in exact limits, features, and pricing from api-model.md
    // =========================================================================

    @Test
    void factualRegression_qwen37Flash_strictlyMatchesApiModel() {
        ModelProfile profile = catalogService.findByModelName("qwen3.7-flash")
                .orElseThrow(() -> new AssertionError("qwen3.7-flash not found"));

        // Limits: 1M context, 128K output
        assertEquals(1048576L, profile.limits().contextWindowTokens());
        assertEquals(131072L, profile.limits().maxOutputTokens());

        // Pricing: 0.20 in, 0.80 out
        assertEquals(0, BigDecimal.valueOf(0.20).compareTo(profile.pricing().inputPerMillionTokens()));
        assertEquals(0, BigDecimal.valueOf(0.80).compareTo(profile.pricing().outputPerMillionTokens()));

        // Features: Vision & Tool & Structured all SUPPORTED
        assertEquals(SupportStatus.SUPPORTED, profile.features().toolCalling());
        assertEquals(SupportStatus.SUPPORTED, profile.features().structuredOutput());
        assertEquals(SupportStatus.SUPPORTED, profile.features().vision());
    }

    @Test
    void factualRegression_qwen37Plus_strictlyMatchesApiModel() {
        ModelProfile profile = catalogService.findByModelName("qwen3.7-plus")
                .orElseThrow(() -> new AssertionError("qwen3.7-plus not found"));

        // Limits: 1M context, 128K output
        assertEquals(1048576L, profile.limits().contextWindowTokens());
        assertEquals(131072L, profile.limits().maxOutputTokens());

        // Pricing: 2.00 in, 8.00 out (base price)
        assertEquals(0, BigDecimal.valueOf(2.00).compareTo(profile.pricing().inputPerMillionTokens()));
        assertEquals(0, BigDecimal.valueOf(8.00).compareTo(profile.pricing().outputPerMillionTokens()));

        // Features: Vision & Tool & Structured all SUPPORTED
        assertEquals(SupportStatus.SUPPORTED, profile.features().toolCalling());
        assertEquals(SupportStatus.SUPPORTED, profile.features().structuredOutput());
        assertEquals(SupportStatus.SUPPORTED, profile.features().vision());
    }

    @Test
    void factualRegression_qwen38Max_strictlyMatchesApiModel() {
        ModelProfile profile = catalogService.findByModelName("qwen3.8-max")
                .orElseThrow(() -> new AssertionError("qwen3.8-max not found"));

        // Limits: 1M context, 128K output
        assertEquals(1048576L, profile.limits().contextWindowTokens());
        assertEquals(131072L, profile.limits().maxOutputTokens());

        // Pricing: 12.00 in, 36.00 out
        assertEquals(0, BigDecimal.valueOf(12.00).compareTo(profile.pricing().inputPerMillionTokens()));
        assertEquals(0, BigDecimal.valueOf(36.00).compareTo(profile.pricing().outputPerMillionTokens()));

        // Features: Vision & Tool & Structured all SUPPORTED
        assertEquals(SupportStatus.SUPPORTED, profile.features().toolCalling());
        assertEquals(SupportStatus.SUPPORTED, profile.features().structuredOutput());
        assertEquals(SupportStatus.SUPPORTED, profile.features().vision());
    }

    // =========================================================================
    // Case 20: All enabled models have their provider key configured
    // =========================================================================

    @Test
    void case20_allEnabledModels_haveConfiguredProvider() {
        List<ModelProfile> enabledModels = catalogService.getEnabledModels();
        assertFalse(enabledModels.isEmpty(), "Enabled models list must not be empty");

        for (ModelProfile profile : enabledModels) {
            String providerKey = profile.provider().toLowerCase();
            boolean providerConfigured = providerProperties.getModelProviders().containsKey(providerKey);
            assertTrue(providerConfigured,
                    String.format("Enabled model [%s] specifies provider [%s], but no provider config found in modelProviders",
                            profile.id(), providerKey));
        }
    }

    // =========================================================================
    // Case 21: All enabled models can be resolved by ProviderRegistryService
    // =========================================================================

    @Test
    void case21_allEnabledModels_areResolvableByProviderRegistry() {
        List<ModelProfile> enabledModels = catalogService.getEnabledModels();
        assertFalse(enabledModels.isEmpty(), "Enabled models list must not be empty");

        for (ModelProfile profile : enabledModels) {
            ModelProviderProperties.ProviderConfig config = providerRegistryService.findProviderConfig(profile.modelName());
            assertNotNull(config,
                    String.format("Enabled model [%s] with modelName [%s] could not be resolved by ModelProviderRegistryService",
                            profile.id(), profile.modelName()));
            assertNotNull(config.getBaseUrl(), "Resolved provider must have non-null baseUrl");
        }
    }
}
