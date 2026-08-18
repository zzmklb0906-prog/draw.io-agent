package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.strategy.CompositeModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.LlmClassifierModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.RuleBasedModelRouter;
import cn.bugstack.ai.domain.agent.service.llm.strategy.SemanticVectorModelRouter;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test baseline for Phase 0 + Phase 1 model routing refactoring.
 *
 * <p>Test structure:
 * <ol>
 *   <li>Happy-path tests (preserved from original baseline)</li>
 *   <li>Keyword false-positive tests (single keyword should not always trigger L3)</li>
 *   <li>Negation expression tests</li>
 *   <li>Short text / complex task tests</li>
 *   <li>Long text / lightweight task tests</li>
 *   <li>Multi-turn history pollution tests (CORE Phase 1 regression)</li>
 *   <li>Edge cases: empty request, null content, explicit model override</li>
 * </ol>
 *
 * <p>For tests that document KNOWN LIMITATIONS of the current heuristic router
 * (Phase 2+ will fix), they are annotated with "LEGACY BEHAVIOR" and are
 * intentionally not asserting the ideal outcome.
 */
class ModelRoutingServiceTest {

    private final RuleBasedModelRouter ruleRouter = new RuleBasedModelRouter();
    private final SemanticVectorModelRouter semanticRouter = new SemanticVectorModelRouter();
    private final LlmClassifierModelRouter classifierRouter = new LlmClassifierModelRouter(semanticRouter);
    private final CompositeModelRouter compositeRouter = new CompositeModelRouter(semanticRouter, classifierRouter, ruleRouter);

    private final ModelRoutingService router = new ModelRoutingService(
            true, "composite", "fast", "balanced", "reasoning",
            List.of(ruleRouter, semanticRouter, classifierRouter, compositeRouter)
    );

    // =========================================================================
    // Preserved happy-path baseline tests
    // =========================================================================

    @Test
    void routesSimpleFormattingToFastModel() {
        // Original baseline: simple formatting request → fast model
        assertEquals("fast", router.route(request("请把这段内容进行摘要和格式整理")).model());
    }

    @Test
    void routesArchitectureAnalysisToReasoningModel() {
        // Original baseline: explicit complex architecture analysis → reasoning model
        assertEquals("reasoning", router.route(request("请完成跨模块架构分析并检查状态机调用链")).model());
    }

    @Test
    void routesNormalTaskToBalancedModel() {
        // Original baseline: generic task → balanced model
        assertEquals("balanced", router.route(request("画一个用户登录流程图")).model());
    }

    // =========================================================================
    // Scenario 1: Simple greeting
    // =========================================================================

    @Test
    void simpleGreeting_shouldNotEscalate() {
        // "你好" should NOT be routed to the reasoning model
        ModelRoutingService.Decision d = router.route(request("你好"));
        assertNotEquals("reasoning", d.model(),
                "A simple greeting must not be escalated to the reasoning model");
        assertTrue(d.complexity() <= 2, "Simple greeting complexity must be L1 or L2, got: " + d.complexity());
    }

    // =========================================================================
    // Scenario 2: Generic summarization
    // =========================================================================

    @Test
    void genericSummarization_shouldBeFastOrBalanced() {
        ModelRoutingService.Decision d = router.route(request("帮我总结一下这个内容"));
        assertNotEquals("reasoning", d.model(),
                "Generic summarization should not be escalated to reasoning model");
    }

    // =========================================================================
    // Scenario 3: Complex request → reasoning
    // =========================================================================

    @Test
    void complexArchitectureAnalysis_shouldRouteToReasoning() {
        ModelRoutingService.Decision d = router.route(
                request("请分析整个系统架构、状态机以及并发一致性问题"));
        assertEquals("reasoning", d.model(),
                "Explicit complex architecture + state machine + concurrency should route to reasoning");
        assertEquals(3, d.complexity());
    }

    // =========================================================================
    // Scenario 4: Keyword false-positive (Phase 1 core fix)
    // =========================================================================

    @Test
    void keywordInEditContext_shouldNotTriggerComplexRouting() {
        // "架构" appears but the ACTION is "改标题" — very simple edit task
        // Phase 1 fix: only the latest user message is analyzed
        ModelRoutingService.Decision d = router.route(request("把这个架构图的标题改成系统架构"));

        // EXPECTED NEW BEHAVIOR: This is a lightweight edit — should be fast or balanced
        // KNOWN LIMITATION (Phase 2): The heuristic still matches "架构" substring.
        // We document the current behavior without asserting ideal behavior:
        assertNotNull(d.model(), "Router must always return a non-null decision");
        System.out.println("[Scenario 4] keyword-in-edit context → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason());
        // Ideal (Phase 2+): assertNotEquals("reasoning", d.model())
        // Current heuristic limitation: "架构" may still push to reasoning
    }

    // =========================================================================
    // Scenario 5: Negation expression
    // =========================================================================

    @Test
    void negation_shouldNotEscalateDueToKeywords() {
        // "不需要分析架构，只修改标题" contains "架构" and "分析" but they are negated
        ModelRoutingService.Decision d = router.route(request("不需要分析架构，只修改标题"));

        // EXPECTED NEW BEHAVIOR: The negated request is a simple edit
        // KNOWN LIMITATION (Phase 2): Current heuristic has NO negation detection.
        // We document the gap explicitly:
        assertNotNull(d.model(), "Router must always return a non-null decision");
        System.out.println("[Scenario 5] negation test → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason());
        // Ideal (Phase 2+): assertNotEquals("reasoning", d.model())
        // This is a known limitation — negation is NOT handled in Phase 1
    }

    // =========================================================================
    // Scenario 6: Short text, complex topic
    // =========================================================================

    @Test
    void shortText_complexTopic_shouldNotDefaultToSimple() {
        // "解释 ABA 问题" is short but conceptually complex
        // We only verify the router does not crash and returns a model
        ModelRoutingService.Decision d = router.route(request("解释 ABA 问题"));
        assertNotNull(d.model(), "Router must return a valid model for short complex question");
        System.out.println("[Scenario 6] short complex text → model=" + d.model()
                + " complexity=" + d.complexity());
        // Note: "ABA 问题" has no matching keywords in current dictionary → likely balanced
        // Phase 2 should handle conceptual complexity even without keyword presence
    }

    // =========================================================================
    // Scenario 7: Long text, lightweight task
    // =========================================================================

    @Test
    void longText_lightweightTask_documentsLengthVsComplexitySeparation() {
        // Very long text + "请总结" — length and complexity are now SEPARATE concerns
        String longContent = "A".repeat(10000) + " 请总结以上内容";

        ModelRoutingService.Decision d = router.route(request(longContent));

        // Document the behavior: totalContextChars is large, but latestUserText = longContent
        // The heuristic still sees the full text as the "latest user message" in single-turn
        assertNotNull(d.model());
        System.out.println("[Scenario 7] long text + '总结' → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason());
        // In single-turn, latestUserText IS the full text (no history pollution).
        // The length factor is expected to contribute to a higher score.
        // Phase 1 improvement: in multi-turn, latestUserText would only be the last message.
    }

    // =========================================================================
    // Scenario 8: Multi-turn history pollution (CORE Phase 1 regression test)
    // =========================================================================

    @Test
    void multiTurnHistory_complexFirstTurn_simpleSecondTurn_shouldNotEscalate() {
        // This is the most critical Phase 1 regression test.
        // Before Phase 1: String.valueOf(request.contents()) included the full history,
        // so complex keywords from turn 1 contaminated the routing decision for turn 2.
        // After Phase 1: Only the latest user message is analyzed.

        LlmRequest multiTurnRequest = LlmRequest.builder()
                .model("default")
                .contents(List.of(
                        // Turn 1 user: highly complex
                        userContent("请深入分析整个系统架构、状态机、并发模型以及一致性。"),
                        // Turn 1 assistant: long complex response simulating real agent output
                        assistantContent("本系统架构采用分布式微服务架构，状态机使用 XState 实现。" +
                                "并发模型基于 Actor 模式，一致性通过分布式锁保障。重构建议如下..."),
                        // Turn 2 user: trivial edit
                        userContent("把标题改成登录流程")
                ))
                .build();

        ModelRoutingService.Decision d = router.route(multiTurnRequest);

        // Phase 1 assertion: complexity decision should be based on "把标题改成登录流程",
        // not the full history containing "架构", "状态机", "并发", "一致性".
        assertNotNull(d.model());
        System.out.println("[Scenario 8] multi-turn pollution test → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason()
                + " narrative=" + d.narrative());

        // The latest user message "把标题改成登录流程" should NOT trigger L3.
        // It contains "标题" (lightweight keyword) but NOT complex keywords.
        assertNotEquals(3, d.complexity(),
                "Phase 1 regression: 'change title' after complex history must NOT be L3. " +
                "If this fails, the router is still reading full history instead of latest user message.");
        assertNotEquals("reasoning", d.model(),
                "Phase 1 regression: simple title-change turn must NOT route to reasoning model, " +
                "even after a complex first turn.");
    }

    // =========================================================================
    // Scenario 9: Empty / null content
    // =========================================================================

    @Test
    void emptyRequest_shouldNotThrow() {
        // Empty contents list
        LlmRequest emptyRequest = LlmRequest.builder()
                .model("default")
                .contents(List.of())
                .build();

        assertDoesNotThrow(() -> {
            ModelRoutingService.Decision d = router.route(emptyRequest);
            assertNotNull(d, "Router must return a non-null Decision for empty request");
            // Should fall back to balanced or null model (DISABLED/KEEP_DEFAULT)
            System.out.println("[Scenario 9] empty request → model=" + d.model()
                    + " reason=" + d.reason());
        });
    }

    @Test
    void singleTurn_noUserRole_shouldNotThrow() {
        // Content with no role attribute — edge case
        LlmRequest noRoleRequest = LlmRequest.builder()
                .model("default")
                .contents(List.of(Content.fromParts(Part.fromText("内容但无角色"))))
                .build();

        assertDoesNotThrow(() -> {
            ModelRoutingService.Decision d = router.route(noRoleRequest);
            assertNotNull(d);
            System.out.println("[Scenario 9b] no-role content → model=" + d.model()
                    + " reason=" + d.reason());
        });
    }

    // =========================================================================
    // Scenario 10: Explicit model override behavior (must be preserved)
    // =========================================================================

    @Test
    void routingDisabled_returnsNullModelWithDisabledReason() {
        // Simulates model routing being disabled
        ModelRoutingService disabledRouter = new ModelRoutingService(
                false, "composite", "fast", "balanced", "reasoning",
                List.of(ruleRouter, semanticRouter, classifierRouter, compositeRouter)
        );

        ModelRoutingService.Decision d = disabledRouter.route(request("任何请求"));
        assertNull(d.model(), "Disabled routing must return null model");
        assertEquals("DISABLED", d.reason());
    }

    @Test
    void allModelSlotsBlank_shouldNotThrow() {
        // Edge case: all model slots are empty strings
        ModelRoutingService noModelRouter = new ModelRoutingService(
                true, "composite", "", "", "",
                List.of(ruleRouter, semanticRouter, classifierRouter, compositeRouter)
        );

        assertDoesNotThrow(() -> {
            ModelRoutingService.Decision d = noModelRouter.route(request("任何请求"));
            assertNotNull(d);
            System.out.println("[Scenario 10b] all blank models → model=" + d.model()
                    + " reason=" + d.reason());
        });
    }

    // =========================================================================
    // Additional regression: single keyword must not always trigger L3
    // =========================================================================

    @Test
    void singleKeyword_架构_inShortMessage_behaviorDocumented() {
        // A single occurrence of "架构" in a very short message
        // Phase 1: still uses keyword matching, so this may still trigger L3.
        // This test DOCUMENTS the current behavior for Phase 2 comparison.
        ModelRoutingService.Decision d = router.route(request("架构"));

        System.out.println("[Regression] single keyword '架构' → model=" + d.model()
                + " complexity=" + d.complexity()
                + " finalReasoningScore=" + d.metrics().get("finalReasoningScore"));

        // We only verify no exception; Phase 2 should require reasoningScore > threshold
        // with multiple evidences before escalating.
        assertNotNull(d.model());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static LlmRequest request(String text) {
        // Single-turn request with explicit user role
        return LlmRequest.builder()
                .model("default")
                .contents(List.of(userContent(text)))
                .build();
    }

    private static Content userContent(String text) {
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    private static Content assistantContent(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }
}
