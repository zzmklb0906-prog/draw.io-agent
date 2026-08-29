package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.armory.matter.plugin.CustomConfigPlugin;
import cn.bugstack.ai.domain.agent.service.chat.CustomApiConfigManager;
import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogProperties;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderProperties;
import cn.bugstack.ai.domain.agent.service.llm.provider.ModelProviderRegistryService;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.DefaultModelConstraintFilter;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationService;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.AnalystAgentRequirementPolicy;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.CurrentTurnVisionDetector;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.DrawerAgentRequirementPolicy;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.ReviewerAgentRequirementPolicy;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RuleBasedRoutingRequirementAnalyzer;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskTypeDetector;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DefaultModelRanker;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScoringProperties;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.WeightedModelScorer;
import cn.bugstack.ai.domain.agent.service.llm.strategy.CompositeModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.LlmClassifierModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.RuleBasedModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.SemanticVectorModelRouter;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration and edge case tests for {@link CustomConfigPlugin}
 * covering dynamic routing production selection, bounded fallback, and strict explicit model validation.
 */
class CustomConfigPluginTest {

    private ModelCatalogService catalogService;
    private ModelProviderRegistryService providerRegistryService;
    private ModelRoutingService legacyRoutingService;
    private RoutingContextFactory routingContextFactory;
    private RoutingRequirementService requirementService;
    private ModelConstraintFilteringService constraintFilteringService;
    private DynamicModelRankingService dynamicRankingService;
    private RoutingEvaluationService evaluationService;
    private LightweightMonitorService monitorService;

    @BeforeEach
    void setUp() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        List<PropertySource<?>> devSources = loader.load("application-dev.yml", new ClassPathResource("application-dev.yml"));
        for (PropertySource<?> source : devSources) {
            propertySources.addLast(source);
        }

        List<PropertySource<?>> catalogSources = loader.load("model-catalog.yml", new ClassPathResource("model-catalog.yml"));
        for (PropertySource<?> source : catalogSources) {
            propertySources.addLast(source);
        }

        ConfigurationPropertySources.attach(environment);
        Binder binder = Binder.get(environment);

        ModelCatalogProperties catalogProperties = binder.bind("ai.agent.model-catalog", ModelCatalogProperties.class)
                .orElseGet(ModelCatalogProperties::new);
        this.catalogService = new ModelCatalogService(catalogProperties);

        ModelProviderProperties providerProperties = binder.bind("ai.agent", ModelProviderProperties.class)
                .orElseGet(ModelProviderProperties::new);
        this.providerRegistryService = new ModelProviderRegistryService(providerProperties);

        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        RuleBasedModelRouter ruleRouter = new RuleBasedModelRouter(extractor);
        SemanticVectorModelRouter semanticRouter = new SemanticVectorModelRouter(extractor);
        LlmClassifierModelRouter classifierRouter = new LlmClassifierModelRouter(extractor, semanticRouter);
        CompositeModelRouter compositeRouter = new CompositeModelRouter(extractor, semanticRouter, classifierRouter, ruleRouter);
        this.legacyRoutingService = new ModelRoutingService(
                true, "composite", "qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max",
                List.of(ruleRouter, semanticRouter, classifierRouter, compositeRouter)
        );

        this.monitorService = Mockito.mock(LightweightMonitorService.class);
        Mockito.when(monitorService.activeAgentName(Mockito.anyString())).thenReturn("agent_analyst");

        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        this.routingContextFactory = new RoutingContextFactory(extractor, tokenEstimator);

        TaskTypeDetector taskTypeDetector = new TaskTypeDetector();
        CurrentTurnVisionDetector visionDetector = new CurrentTurnVisionDetector(extractor);
        RuleBasedRoutingRequirementAnalyzer analyzer = new RuleBasedRoutingRequirementAnalyzer(
                taskTypeDetector, visionDetector,
                List.of(new AnalystAgentRequirementPolicy(), new DrawerAgentRequirementPolicy(), new ReviewerAgentRequirementPolicy())
        );
        this.requirementService = new RoutingRequirementService(analyzer);

        DefaultModelConstraintFilter constraintFilter = new DefaultModelConstraintFilter();
        this.constraintFilteringService = new ModelConstraintFilteringService(catalogService, constraintFilter);

        WeightedModelScorer scorer = new WeightedModelScorer(new ModelScoringProperties());
        DefaultModelRanker ranker = new DefaultModelRanker(scorer);
        this.dynamicRankingService = new DynamicModelRankingService(ranker);

        this.evaluationService = new RoutingEvaluationService(List.of(), scorer, catalogService);
    }

    @AfterEach
    void tearDown() {
        CustomApiConfigManager.clearConfig("session-test");
    }

    @Test
    void dynamicRoutingEnabled_routesSimpleTaskToDynamicTopCandidate() {
        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, requirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, true
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-1");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("修改标题为系统设计"))).build()));

        plugin.beforeModelCallback(context, requestBuilder).blockingGet();

        // Exactly one model must be set
        assertTrue(requestBuilder.build().model().isPresent());
        assertEquals("qwen3.7-flash", requestBuilder.build().model().get(),
                "Simple edit task should be dynamically routed to cheapest sufficient Fast model (qwen3.7-flash)");
    }

    @Test
    void explicitValidModel_winsAndOverridesAutomaticRouting() {
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                .customModelSelected(true)
                .model("qwen3.8-max")
                .build();
        CustomApiConfigManager.setConfig("session-test", config);

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, requirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, true
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-2");

        // Simple edit would normally select Flash, but explicit model qwen3.8-max must win unconditionally
        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("修改标题为系统设计"))).build()));

        plugin.beforeModelCallback(context, requestBuilder).blockingGet();

        assertEquals("qwen3.8-max", requestBuilder.build().model().orElseThrow(),
                "Valid explicit model selection must unconditionally override automatic routing");
    }

    @Test
    void explicitDisabledModel_failsFastWithException() {
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                .customModelSelected(true)
                .model("qwen3.7-max") // Disabled in model-catalog.yml
                .build();
        CustomApiConfigManager.setConfig("session-test", config);

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, requirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, true
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-3");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("测试消息"))).build()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                plugin.beforeModelCallback(context, requestBuilder).blockingGet());

        assertTrue(ex.getMessage().contains("disabled in catalog"),
                "Must fail fast when explicit model is disabled, got: " + ex.getMessage());
    }

    @Test
    void explicitModelViolatingHardConstraint_failsFastWithException() {
        // Build catalog containing an enabled model that lacks vision
        ModelCatalogProperties testProps = new ModelCatalogProperties();
        ModelCatalogProperties.ModelConfig textOnlyModel = ModelCatalogProperties.ModelConfig.builder()
                .id("qwen-text-only")
                .provider("qwen")
                .modelName("qwen-text-only")
                .tier("BALANCED")
                .enabled(true) // Enabled model in catalog
                .capabilities(new ModelCatalogProperties.CapabilitiesConfig(80, 80, 80, 80, 80, 0, 80))
                .features(new ModelCatalogProperties.FeaturesConfig("SUPPORTED", "SUPPORTED", "UNSUPPORTED")) // lacks vision!
                .limits(new ModelCatalogProperties.LimitsConfig(131072, 8192))
                .pricing(new ModelCatalogProperties.PricingConfig(java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, "CNY"))
                .build();
        testProps.setModels(List.of(textOnlyModel));
        ModelCatalogService testCatalog = new ModelCatalogService(testProps);
        ModelConstraintFilteringService testFilterService = new ModelConstraintFilteringService(
                testCatalog, new DefaultModelConstraintFilter()
        );

        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                .customModelSelected(true)
                .model("qwen-text-only")
                .build();
        CustomApiConfigManager.setConfig("session-test", config);

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, requirementService, testFilterService,
                dynamicRankingService, evaluationService, testCatalog, true
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-4");

        Part imagePart = Part.builder()
                .inlineData(Blob.builder().mimeType("image/png").data(new byte[]{1, 2, 3}).build())
                .build();
        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(imagePart, Part.fromText("分析图"))).build()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                plugin.beforeModelCallback(context, requestBuilder).blockingGet());

        assertTrue(ex.getMessage().contains("violates request hard constraints"),
                "Must fail fast when explicit enabled model violates constraints, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("VISION_UNSUPPORTED"),
                "Violation detail must specify VISION_UNSUPPORTED, got: " + ex.getMessage());
    }

    @Test
    void explicitUncatalogedModel_withoutProviderOrTuple_failsFast() {
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                .customModelSelected(true)
                .model("custom-unregistered-model") // Uncataloged and no provider
                .build();
        CustomApiConfigManager.setConfig("session-test", config);

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, requirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, true
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-5");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("测试消息"))).build()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                plugin.beforeModelCallback(context, requestBuilder).blockingGet());

        assertTrue(ex.getMessage().contains("cannot be executed"),
                "Uncataloged explicit model without provider or custom tuple must fail fast");
    }

    @Test
    void explicitUncatalogedModel_withCustomTuple_succeeds() {
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                .customModelSelected(true)
                .model("custom-unregistered-model")
                .baseUrl("https://my-custom-llm.example.com/v1")
                .apiKey("test-secret-key")
                .build();
        CustomApiConfigManager.setConfig("session-test", config);

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, requirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, true
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-6");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("测试消息"))).build()));

        plugin.beforeModelCallback(context, requestBuilder).blockingGet();

        assertEquals("custom-unregistered-model", requestBuilder.build().model().orElseThrow());
    }

    @Test
    void dynamicRoutingDisabled_fallsBackToLegacyRouter() {
        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, requirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, false // dynamic disabled
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-7");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("画一个用户登录流程图"))).build()));

        plugin.beforeModelCallback(context, requestBuilder).blockingGet();

        // When dynamic routing is disabled, legacy router selects balanced model
        assertEquals("qwen3.7-plus", requestBuilder.build().model().orElseThrow());
    }

    @Test
    void explicitCatalogModel_whenAnalysisFails_failsClosedWithValidationUnavailableException() {
        RoutingRequirementService failingRequirementService = Mockito.mock(RoutingRequirementService.class);
        Mockito.when(failingRequirementService.tryAnalyze(Mockito.any())).thenReturn(java.util.Optional.empty());

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, failingRequirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, true
        );

        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                .customModelSelected(true)
                .model("qwen3.7-flash") // Enabled catalog model
                .build();
        CustomApiConfigManager.setConfig("session-test", config);

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-analysis-empty");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("测试"))).build()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                plugin.beforeModelCallback(context, requestBuilder).blockingGet());

        assertTrue(ex.getMessage().contains("Validation unavailable for cataloged explicit model [qwen3.7-flash]"),
                "Explicit catalog model must fail closed when analysis is unavailable: " + ex.getMessage());
    }

    @Test
    void explicitCatalogModel_whenAnalysisThrowsException_failsClosed() {
        RoutingRequirementService errorRequirementService = Mockito.mock(RoutingRequirementService.class);
        Mockito.when(errorRequirementService.tryAnalyze(Mockito.any()))
                .thenThrow(new RuntimeException("Simulated intent analyzer failure"));

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, errorRequirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, true
        );

        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                .customModelSelected(true)
                .model("qwen3.7-flash")
                .build();
        CustomApiConfigManager.setConfig("session-test", config);

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-analysis-error");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("测试"))).build()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                plugin.beforeModelCallback(context, requestBuilder).blockingGet());

        assertTrue(ex.getMessage().contains("Validation unavailable for cataloged explicit model"),
                "Explicit catalog model must fail closed when analysis throws: " + ex.getMessage());
    }

    @Test
    void automaticRouting_whenDynamicAnalysisFails_fallsBackToLegacyRouter() {
        RoutingRequirementService failingRequirementService = Mockito.mock(RoutingRequirementService.class);
        Mockito.when(failingRequirementService.tryAnalyze(Mockito.any()))
                .thenThrow(new RuntimeException("Simulated pipeline breakdown"));

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, failingRequirementService, constraintFilteringService,
                dynamicRankingService, evaluationService, catalogService, true
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-fallback-analysis-error");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("画一个用户登录流程图"))).build()));

        // Must NOT throw exception; must fall back to legacy router gracefully
        assertDoesNotThrow(() -> plugin.beforeModelCallback(context, requestBuilder).blockingGet());

        assertEquals("qwen3.7-plus", requestBuilder.build().model().orElseThrow(),
                "Automatic routing should fall back to legacy router when dynamic pipeline encounters error");
    }

    @Test
    void automaticRouting_whenRankingProducesNoCandidate_fallsBackToLegacyRouter() {
        DynamicModelRankingService emptyRankingService = Mockito.mock(DynamicModelRankingService.class);
        Mockito.when(emptyRankingService.rank(Mockito.any(), Mockito.any())).thenReturn(
                cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RankingResult.empty()
        );

        CustomConfigPlugin plugin = new CustomConfigPlugin(
                legacyRoutingService, monitorService, providerRegistryService,
                routingContextFactory, requirementService, constraintFilteringService,
                emptyRankingService, evaluationService, catalogService, true
        );

        CallbackContext context = Mockito.mock(CallbackContext.class);
        Mockito.when(context.sessionId()).thenReturn("session-test");
        Mockito.when(context.invocationId()).thenReturn("inv-fallback-empty-candidates");

        LlmRequest.Builder requestBuilder = LlmRequest.builder()
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("画一个用户登录流程图"))).build()));

        assertDoesNotThrow(() -> plugin.beforeModelCallback(context, requestBuilder).blockingGet());

        assertEquals("qwen3.7-plus", requestBuilder.build().model().orElseThrow(),
                "Automatic routing should fall back to legacy router when ranking produces no candidates");
    }
}
