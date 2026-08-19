package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.CandidateModelSelector;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.DefaultModelCapabilityFilter;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.ModelCapabilityFilter;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 — Capability-aware Model Selection Tests
 */
@DisplayName("Phase 3 Capability-aware Model Selection Tests")
public class CapabilityFilterTest {

    private final ModelCapabilityFilter filter = new DefaultModelCapabilityFilter();

    private ModelProfile modelA; // tool=true, vision=false, structured=true, context=131072
    private ModelProfile modelB; // tool=false, vision=false, structured=false, context=8192
    private ModelProfile modelC; // tool=true, vision=true, structured=true, context=65536
    private ModelProfile modelDisabled; // disabled

    @BeforeEach
    void setUp() {
        modelA = createModel("model-a", "Model A", true, true, false, true, 131072, 8192);
        modelB = createModel("model-b", "Model B", true, false, false, false, 8192, 2048);
        modelC = createModel("model-c", "Model C", true, true, true, true, 65536, 4096);
        modelDisabled = createModel("model-d", "Model D", false, true, true, true, 131072, 8192);
    }

    // =========================================================================
    // Case 1: Tool Calling Requirement Filter
    // =========================================================================
    @Test
    @DisplayName("Case 1: Tool Calling 需求过滤 - 验证仅保留支持工具调用的模型")
    void case1_toolCallingFilter() {
        RoutingRequirement req = requirement(true, false, false, 4096L);
        List<ModelProfile> filtered = filter.filter(req, List.of(modelA, modelB, modelC, modelDisabled));

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(m -> m.id().equals("model-a")));
        assertTrue(filtered.stream().anyMatch(m -> m.id().equals("model-c")));
        assertFalse(filtered.stream().anyMatch(m -> m.id().equals("model-b")));
        assertFalse(filtered.stream().anyMatch(m -> m.id().equals("model-d")));
    }

    // =========================================================================
    // Case 2: Vision Requirement Filter
    // =========================================================================
    @Test
    @DisplayName("Case 2: Vision 需求过滤 - 验证仅保留支持视觉多模态的模型")
    void case2_visionFilter() {
        RoutingRequirement req = requirement(false, true, false, 4096L);
        List<ModelProfile> filtered = filter.filter(req, List.of(modelA, modelB, modelC));

        assertEquals(1, filtered.size());
        assertEquals("model-c", filtered.get(0).id());
    }

    // =========================================================================
    // Case 3: Structured Output Requirement Filter
    // =========================================================================
    @Test
    @DisplayName("Case 3: Structured Output 需求过滤 - 验证仅保留支持结构化输出的模型")
    void case3_structuredOutputFilter() {
        RoutingRequirement req = requirement(false, false, true, 4096L);
        List<ModelProfile> filtered = filter.filter(req, List.of(modelA, modelB, modelC));

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(m -> m.id().equals("model-a")));
        assertTrue(filtered.stream().anyMatch(m -> m.id().equals("model-c")));
        assertFalse(filtered.stream().anyMatch(m -> m.id().equals("model-b")));
    }

    // =========================================================================
    // Case 4: Context Window Capacity Filter
    // =========================================================================
    @Test
    @DisplayName("Case 4: Context Window 过滤 - 验证 32000 tokens 需求过滤 8k 模型")
    void case4_contextWindowFilter() {
        RoutingRequirement req = requirement(false, false, false, 32000L);
        List<ModelProfile> filtered = filter.filter(req, List.of(modelA, modelB, modelC));

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(m -> m.id().equals("model-a")));
        assertTrue(filtered.stream().anyMatch(m -> m.id().equals("model-c")));
        assertFalse(filtered.stream().anyMatch(m -> m.id().equals("model-b")));
    }

    // =========================================================================
    // Case 5: No Special Requirement Filter
    // =========================================================================
    @Test
    @DisplayName("Case 5: 无特殊要求 - 验证返回全部 enabled 模型")
    void case5_noSpecialRequirementFilter() {
        RoutingRequirement req = requirement(false, false, false, 0L);
        List<ModelProfile> filtered = filter.filter(req, List.of(modelA, modelB, modelC, modelDisabled));

        assertEquals(3, filtered.size());
        assertFalse(filtered.stream().anyMatch(m -> m.id().equals("model-d")));
    }

    // =========================================================================
    // Case 6: Empty Candidates Safe Handling
    // =========================================================================
    @Test
    @DisplayName("Case 6: 无候选模型与防御性安全处理 - 验证安全返回空列表，无异常")
    void case6_emptyCandidatesHandling() {
        RoutingRequirement req = requirement(true, true, true, 200000L);

        List<ModelProfile> emptyResult = filter.filter(req, List.of());
        assertNotNull(emptyResult);
        assertTrue(emptyResult.isEmpty());

        List<ModelProfile> nullResult = filter.filter(req, null);
        assertNotNull(nullResult);
        assertTrue(nullResult.isEmpty());

        List<ModelProfile> nullReqResult = filter.filter(null, List.of(modelA));
        assertEquals(1, nullReqResult.size());
    }

    // =========================================================================
    // Case 7: CandidateModelSelector Integration Test
    // =========================================================================
    @Test
    @DisplayName("Case 7: CandidateModelSelector 集成测试 - 验证与 ModelCatalogService 协同工作")
    void case7_candidateModelSelectorIntegration() {
        ModelCatalogService catalogService = new ModelCatalogService(new ModelCatalogProperties());
        catalogService.registerModel(modelA);
        catalogService.registerModel(modelB);
        catalogService.registerModel(modelC);

        CandidateModelSelector selector = new CandidateModelSelector(catalogService, filter);

        RoutingRequirement visionReq = requirement(false, true, false, 4096L);
        List<ModelProfile> candidates = selector.select(visionReq);

        assertEquals(1, candidates.size());
        assertEquals("model-c", candidates.get(0).id());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ModelProfile createModel(String id,
                                            String name,
                                            boolean enabled,
                                            boolean toolCalling,
                                            boolean vision,
                                            boolean structuredOutput,
                                            long contextWindow,
                                            long maxOutput) {
        return new ModelProfile(
                id,
                "generic",
                name,
                enabled,
                new ModelCapabilities(70, 70, 70, structuredOutput ? 80 : 0, toolCalling ? 80 : 0, vision ? 80 : 0, 70),
                new ModelFeatures(
                        toolCalling ? SupportStatus.SUPPORTED : SupportStatus.UNSUPPORTED,
                        structuredOutput ? SupportStatus.SUPPORTED : SupportStatus.UNSUPPORTED,
                        vision ? SupportStatus.SUPPORTED : SupportStatus.UNSUPPORTED
                ),
                new ModelLimits(contextWindow, maxOutput),
                new ModelPricing(BigDecimal.valueOf(0.001), BigDecimal.valueOf(0.002), "USD")
        );
    }

    private static RoutingRequirement requirement(boolean needTool,
                                                  boolean needVision,
                                                  boolean needStructured,
                                                  long minContext) {
        return new RoutingRequirement(
                TaskType.GENERAL_CHAT,
                30,
                50,
                20,
                needStructured ? 80 : 0,
                needTool ? 80 : 0,
                needVision,
                minContext,
                2048L,
                "agent_test",
                RequirementEvidence.empty()
        );
    }
}
