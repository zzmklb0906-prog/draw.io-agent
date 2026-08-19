package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.strategy.CompositeModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.LlmClassifierModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.RuleBasedModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.SemanticVectorModelRouter;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 — Model Catalog Foundation & Domain Decoupling Tests
 */
@DisplayName("Phase 2 Model Catalog Foundation Tests")
public class ModelCatalogFoundationTest {

    // =========================================================================
    // Case 1: ModelCatalog Registration and Querying
    // =========================================================================
    @Test
    @DisplayName("Case 1: ModelCatalog注册与查询测试 - 验证静态加载与动态注册功能")
    void case1_modelCatalogRegistrationAndQuery() {
        ModelCatalogService catalogService = new ModelCatalogService(new ModelCatalogProperties());
        assertTrue(catalogService.getAllModels().isEmpty());

        ModelProfile dynamicProfile = new ModelProfile(
                "qwen3.7-plus",
                "qwen",
                "qwen-plus-latest",
                true,
                new ModelCapabilities(85, 90, 88, 85, 80, 0, 85),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.UNSUPPORTED),
                new ModelLimits(131072, 8192),
                new ModelPricing(BigDecimal.valueOf(0.004), BigDecimal.valueOf(0.012), "CNY")
        );

        catalogService.registerModel(dynamicProfile);

        assertEquals(1, catalogService.getAllModels().size());
        assertEquals(1, catalogService.getAvailableModels().size());

        Optional<ModelProfile> foundById = catalogService.findById("qwen3.7-plus");
        assertTrue(foundById.isPresent());
        assertEquals("qwen-plus-latest", foundById.get().modelName());

        Optional<ModelProfile> foundByName = catalogService.findByModelName("qwen-plus-latest");
        assertTrue(foundByName.isPresent());
        assertEquals("qwen3.7-plus", foundByName.get().id());
    }

    // =========================================================================
    // Case 2: ModelProfile Capabilities and Features
    // =========================================================================
    @Test
    @DisplayName("Case 2: ModelProfile能力属性测试 - 验证 toolCalling/vision/structuredOutput/limits 判定")
    void case2_modelProfileCapabilities() {
        ModelProfile profile = new ModelProfile(
                "glm-4v",
                "glm",
                "glm-4v-plus",
                true,
                new ModelCapabilities(90, 85, 80, 85, 90, 95, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(131072, 4096),
                new ModelPricing(BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.01), "CNY")
        );

        assertTrue(profile.supportsToolCalling());
        assertTrue(profile.supportsVision());
        assertTrue(profile.supportsStructuredOutput());
        assertEquals(131072L, profile.contextWindow());
        assertEquals(4096L, profile.maxOutputTokens());
    }

    // =========================================================================
    // Case 3: Legacy 3-Slot Configuration Compatibility
    // =========================================================================
    @Test
    @DisplayName("Case 3: 旧配置兼容测试 - 验证 fastModel/balancedModel/reasoningModel 注入与调用依然有效")
    void case3_legacyConfigurationCompatibility() {
        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        RoutingContextFactory contextFactory = new RoutingContextFactory(extractor, tokenEstimator);

        RuleBasedModelRouter ruleRouter = new RuleBasedModelRouter(extractor);
        SemanticVectorModelRouter semanticRouter = new SemanticVectorModelRouter(extractor);
        LlmClassifierModelRouter classifierRouter = new LlmClassifierModelRouter(extractor, semanticRouter);
        CompositeModelRouter compositeRouter = new CompositeModelRouter(extractor, semanticRouter, classifierRouter, ruleRouter);

        ModelCatalogService catalogService = new ModelCatalogService(new ModelCatalogProperties());

        ModelRoutingService routingService = new ModelRoutingService(
                true,
                "composite",
                "qwen3.7-flash",
                "qwen3.7-plus",
                "deepseek-v4-pro",
                List.of(compositeRouter, semanticRouter, classifierRouter, ruleRouter),
                contextFactory,
                catalogService
        );

        assertNotNull(routingService.getModelCatalogService());

        // Fast model test
        LlmRequest fastReq = buildRequest("摘要整理并翻译以下文本");
        ModelRoutingService.Decision fastDecision = routingService.route(fastReq);
        assertEquals("qwen3.7-flash", fastDecision.model());

        // Reasoning model test
        LlmRequest reasoningReq = buildRequest("请设计分布式状态机架构与跨模块一致性方案");
        ModelRoutingService.Decision reasoningDecision = routingService.route(reasoningReq);
        assertEquals("deepseek-v4-pro", reasoningDecision.model());
    }

    // =========================================================================
    // Case 4: RoutingRequirement and RoutingContext Boundary Separation
    // =========================================================================
    @Test
    @DisplayName("Case 4: RoutingRequirement创建与职责分离测试 - 验证请求环境与能力需求解耦")
    void case4_routingRequirementBoundary() {
        // 1. RoutingContext represents runtime environment & user input signals
        LlmRequest request = buildRequest("请读取文件并输出标准 JSON 格式");
        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        RoutingContext context = new RoutingContextFactory(extractor, new HeuristicContextTokenEstimator(extractor))
                .create(request, "agent_drawer", "EXECUTION", false, null);

        assertEquals("agent_drawer", context.agentName());
        assertEquals("EXECUTION", context.workflowStage());
        assertEquals("请读取文件并输出标准 JSON 格式", context.latestUserMessage());

        // 2. RoutingRequirement represents task-level capability requirements (WHAT the task needs)
        RoutingRequirement requirement = new RoutingRequirement(
                TaskType.STRUCTURED_GENERATION,
                70,
                80,
                30,
                90,
                80,
                false,
                8192L,
                4096L,
                context.agentName(),
                new RequirementEvidence(List.of("JSON"), List.of("pattern"), List.of(), 100L, 200L)
        );

        assertTrue(requirement.needToolCalling());
        assertTrue(requirement.needStructuredOutput());
        assertFalse(requirement.needVision());
        assertEquals(3, requirement.estimatedComplexity());
        assertEquals("agent_drawer", requirement.agentName());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LlmRequest buildRequest(String text) {
        return LlmRequest.builder()
                .model("default")
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText(text))).build()))
                .build();
    }
}
