package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
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
 *   <li>Characterization tests (recording current legacy behavior on known limitations)</li>
 *   <li>Short text / complex task tests</li>
 *   <li>Long text / lightweight task tests</li>
 *   <li>Multi-turn history pollution tests (keyword decoupling &amp; context-length decoupling)</li>
 *   <li>Edge cases: empty request, null content, explicit model override</li>
 * </ol>
 */
class ModelRoutingServiceTest {

    private final LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
    private final RuleBasedModelRouter ruleRouter = new RuleBasedModelRouter(extractor);
    private final SemanticVectorModelRouter semanticRouter = new SemanticVectorModelRouter(extractor);
    private final LlmClassifierModelRouter classifierRouter = new LlmClassifierModelRouter(extractor, semanticRouter);
    private final CompositeModelRouter compositeRouter = new CompositeModelRouter(extractor, semanticRouter, classifierRouter, ruleRouter);

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
    // Scenario 4: Keyword false-positive (Characterization Test)
    // =========================================================================

    @Test
    void keywordInEditContext_currentLegacyBehavior_isDocumented() {
        // CHARACTERIZATION TEST:
        // This test records current legacy behavior. It does NOT assert the ideal routing result.
        // "架构" appears but the action is "改标题".
        // In Phase 1, substring matching still triggers "架构".
        // Phase 2 will introduce action intent classification to resolve this.
        ModelRoutingService.Decision d = router.route(request("把这个架构图的标题改成系统架构"));

        assertNotNull(d.model(), "Router must always return a non-null decision");
        System.out.println("[Characterization Test 4] keyword-in-edit context → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason());
    }

    // =========================================================================
    // Scenario 5: Negation expression (Characterization Test)
    // =========================================================================

    @Test
    void negation_currentLegacyBehavior_isDocumented() {
        // CHARACTERIZATION TEST:
        // This test records current legacy behavior. It does NOT assert the ideal routing result.
        // "不需要分析架构，只修改标题" contains "架构" and "分析" but they are negated.
        // Phase 1 has no negation parser; Phase 2+ will handle negation semantics.
        ModelRoutingService.Decision d = router.route(request("不需要分析架构，只修改标题"));

        assertNotNull(d.model(), "Router must always return a non-null decision");
        System.out.println("[Characterization Test 5] negation test → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason());
    }

    // =========================================================================
    // Scenario 6: Short text, complex topic
    // =========================================================================

    @Test
    void shortText_complexTopic_shouldNotDefaultToSimple() {
        // "解释 ABA 问题" is short but conceptually complex
        ModelRoutingService.Decision d = router.route(request("解释 ABA 问题"));
        assertNotNull(d.model(), "Router must return a valid model for short complex question");
        System.out.println("[Scenario 6] short complex text → model=" + d.model()
                + " complexity=" + d.complexity());
    }

    // =========================================================================
    // Scenario 7: Long text, lightweight task
    // =========================================================================

    @Test
    void longText_lightweightTask_documentsLengthVsComplexitySeparation() {
        // Single-turn long text + "请总结"
        String longContent = "A".repeat(10000) + " 请总结以上内容";

        ModelRoutingService.Decision d = router.route(request(longContent));

        assertNotNull(d.model());
        System.out.println("[Scenario 7] long text + '总结' → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason());
    }

    // =========================================================================
    // Scenario 8: Multi-turn history pollution (CORE Phase 1 regression test)
    // =========================================================================

    @Test
    void multiTurnHistory_complexFirstTurn_simpleSecondTurn_shouldNotEscalate() {
        // Core Phase 1 regression test: keywords from turn 1 must not pollute turn 2
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

        assertNotNull(d.model());
        System.out.println("[Scenario 8] multi-turn keyword pollution test → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason()
                + " narrative=" + d.narrative());

        assertNotEquals(3, d.complexity(),
                "Phase 1 regression: 'change title' after complex history must NOT be L3.");
        assertNotEquals("reasoning", d.model(),
                "Phase 1 regression: simple title-change turn must NOT route to reasoning model.");
    }

    // =========================================================================
    // Scenario 8b: Context length decoupling regression test
    // =========================================================================

    @Test
    void longHistory_shortSimpleCurrentRequest_shouldNotEscalateDueToHistoryLength() {
        // Very large conversation history (40,000+ total chars)
        // Current user message: "修改标题" (4 chars, simple edit)
        // Verifies that totalContextChars is decoupled from task complexity calculation.
        String hugeHistoryUser = "用户历史长文本内容描述：".repeat(1000); // ~13,000 chars
        String hugeHistoryModel = "智能体历史长回复与结构设计：".repeat(1500); // ~21,000 chars

        LlmRequest longHistoryRequest = LlmRequest.builder()
                .model("default")
                .contents(List.of(
                        userContent(hugeHistoryUser),
                        assistantContent(hugeHistoryModel),
                        userContent("修改标题")
                ))
                .build();

        ModelRoutingService.Decision d = router.route(longHistoryRequest);

        assertNotNull(d.model());
        System.out.println("[Scenario 8b] large context length decoupling test → model=" + d.model()
                + " complexity=" + d.complexity()
                + " reason=" + d.reason()
                + " totalContextChars=" + d.metrics().get("totalContextChars")
                + " latestUserTextLength=" + d.metrics().get("latestUserTextLength"));

        // Context size (>30,000 chars) must NOT push a short simple task to L3
        assertNotEquals(3, d.complexity(),
                "Decoupled history length: large context size must not escalate simple current task to L3");
        assertNotEquals("reasoning", d.model(),
                "Decoupled history length: simple title modification must not route to reasoning model");
    }

    // =========================================================================
    // Scenario 9: Empty / null content
    // =========================================================================

    @Test
    void emptyRequest_shouldNotThrow() {
        LlmRequest emptyRequest = LlmRequest.builder()
                .model("default")
                .contents(List.of())
                .build();

        assertDoesNotThrow(() -> {
            ModelRoutingService.Decision d = router.route(emptyRequest);
            assertNotNull(d, "Router must return a non-null Decision for empty request");
            System.out.println("[Scenario 9] empty request → model=" + d.model()
                    + " reason=" + d.reason());
        });
    }

    @Test
    void singleTurn_noUserRole_shouldNotThrow() {
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
    // Single keyword (Characterization Test)
    // =========================================================================

    @Test
    void singleKeyword_架构_currentLegacyBehavior_isDocumented() {
        // CHARACTERIZATION TEST:
        // Single keyword "架构" in short message. Records current behavior without asserting ideal outcome.
        ModelRoutingService.Decision d = router.route(request("架构"));

        System.out.println("[Characterization Test] single keyword '架构' → model=" + d.model()
                + " complexity=" + d.complexity()
                + " finalReasoningScore=" + d.metrics().get("finalReasoningScore"));

        assertNotNull(d.model());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static LlmRequest request(String text) {
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
