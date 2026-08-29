package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.*;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RuleBasedRoutingRequirementAnalyzer} and {@link CurrentTurnVisionDetector}.
 *
 * <p>Validates task detection, agent-aware requirement adjustments, context/complexity separation,
 * multimodal isolation (historical vs current turn), score clamping, and zero dependency on ModelCatalogService.</p>
 */
class RoutingRequirementAnalyzerTest {

    private RoutingContextFactory contextFactory;
    private RuleBasedRoutingRequirementAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        this.contextFactory = new RoutingContextFactory(extractor, tokenEstimator);

        TaskTypeDetector detector = new TaskTypeDetector();
        CurrentTurnVisionDetector visionDetector = new CurrentTurnVisionDetector(extractor);
        List<AgentRequirementPolicy> policies = List.of(
                new AnalystAgentRequirementPolicy(),
                new DrawerAgentRequirementPolicy(),
                new ReviewerAgentRequirementPolicy()
        );
        // Note: Zero dependency on ModelCatalogService
        this.analyzer = new RuleBasedRoutingRequirementAnalyzer(detector, visionDetector, policies);
    }

    // =========================================================================
    // Core Cases 1 - 6: Task Detection & Baseline Demands
    // =========================================================================

    @Test
    void case1_simpleEdit_detectedCorrectly() {
        RoutingContext ctx = contextFactory.create(request(userContent("把标题改成用户登录流程")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.SIMPLE_EDIT, req.taskType());
        assertTrue(req.reasoningRequired() <= 60);
    }

    @Test
    void case2_summarize_detectedCorrectly() {
        RoutingContext ctx = contextFactory.create(request(userContent("帮我总结下面这段内容")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.SUMMARIZE, req.taskType());
    }

    @Test
    void case3_diagnose_detectedCorrectly() {
        RoutingContext ctx = contextFactory.create(request(userContent("分析这个 Java 并发死锁问题并排查根因")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.DIAGNOSE, req.taskType());
        assertTrue(req.reasoningRequired() >= 80);
        assertTrue(req.codingRequired() >= 20);
    }

    @Test
    void case4_drawioGeneration_detectedCorrectly() {
        RoutingContext ctx = contextFactory.create(request(userContent("生成微信扫码登录 Draw.io 流程图")), "agent_drawer");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.DRAWIO_GENERATION, req.taskType());
        assertTrue(req.structuredOutputRequired() >= 90);
        assertTrue(req.toolCallingRequired() >= 80);
    }

    @Test
    void case5_drawioReview_detectedCorrectly() {
        RoutingContext ctx = contextFactory.create(request(userContent("检查并修复这段 mxGraph XML 节点连线")), "agent_reviewer");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.DRAWIO_REVIEW, req.taskType());
        assertTrue(req.structuredOutputRequired() >= 85);
        assertTrue(req.reasoningRequired() >= 75);
    }

    @Test
    void case6_negationIntent_prioritizesSimpleEdit() {
        // "不需要分析架构，只修改标题" contains "分析" and "架构" but action intent is edit
        RoutingContext ctx = contextFactory.create(request(userContent("不需要分析架构，只修改标题")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.SIMPLE_EDIT, req.taskType(),
                "High-confidence edit keywords must override analytical keywords");
    }

    @Test
    void analystPolicy_lightweightTask_notRaisedToHighFloors() {
        RoutingContext ctx = contextFactory.create(request(userContent("修改标题为用户流程")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.SIMPLE_EDIT, req.taskType());
        // Must preserve low baseline demands for lightweight tasks rather than forcing high floors (60 / 88 / 90)
        assertEquals(30, req.reasoningRequired(), "Lightweight simple edit reasoning demand must not be forced to floor 60");
        assertEquals(30, req.structuredOutputRequired(), "Lightweight simple edit structured demand must not be forced to floor 88");
        assertEquals(70, req.instructionFollowingRequired(), "Lightweight simple edit instruction demand should be moderate (70)");
    }

    @Test
    void analystPolicy_summarizeAndExtract_treatedAsLightweight_notRaisedToHighFloors() {
        RoutingContext summarizeCtx = contextFactory.create(request(userContent("帮我总结这段会议纪要")), "agent_analyst");
        RoutingRequirement summarizeReq = analyzer.analyze(summarizeCtx);
        assertEquals(TaskType.SUMMARIZE, summarizeReq.taskType());
        assertEquals(40, summarizeReq.reasoningRequired(), "SUMMARIZE reasoning demand must not be forced to floor 60");
        assertEquals(30, summarizeReq.structuredOutputRequired(), "SUMMARIZE structured demand must not be forced to floor 88");
        assertEquals(70, summarizeReq.instructionFollowingRequired(), "SUMMARIZE instruction demand should be preserved at baseline 70");

        RoutingContext extractCtx = contextFactory.create(request(userContent("提取关键实体和配置参数")), "agent_analyst");
        RoutingRequirement extractReq = analyzer.analyze(extractCtx);
        assertEquals(TaskType.EXTRACT, extractReq.taskType());
        assertEquals(45, extractReq.reasoningRequired(), "EXTRACT reasoning demand must not be forced to floor 60");
        assertEquals(60, extractReq.structuredOutputRequired(), "EXTRACT structured demand must not be forced to floor 88");
        assertEquals(75, extractReq.instructionFollowingRequired(), "EXTRACT instruction demand should be preserved at baseline 75");
    }

    @Test
    void taskTypeDetector_negatedArchitectureAnalysis_detectedAsSimpleEdit() {
        RoutingContext ctx = contextFactory.create(request(userContent("不需要分析架构，只修改节点名称")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.SIMPLE_EDIT, req.taskType());
    }

    @Test
    void taskTypeDetector_actionIntentPrecedence_detectedAsSimpleEdit() {
        RoutingContext ctx = contextFactory.create(request(userContent("把这个架构图的标题改成系统架构")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertEquals(TaskType.SIMPLE_EDIT, req.taskType());
    }

    @Test
    void taskTypeDetector_englishEquivalents_detectedAsSimpleEdit() {
        RoutingContext ctx1 = contextFactory.create(request(userContent("rename title to user login")), "agent_analyst");
        assertEquals(TaskType.SIMPLE_EDIT, analyzer.analyze(ctx1).taskType());

        RoutingContext ctx2 = contextFactory.create(request(userContent("just change the title to dashboard")), "agent_analyst");
        assertEquals(TaskType.SIMPLE_EDIT, analyzer.analyze(ctx2).taskType());
    }

    // =========================================================================
    // Agent-aware Requirement Comparison
    // =========================================================================

    @Test
    void agentAware_drawerVsAnalyst_exhibitsProperBias() {
        LlmRequest req = request(userContent("生成微信扫码登录流程图"));

        RoutingContext analystCtx = contextFactory.create(req, "agent_analyst");
        RoutingContext drawerCtx = contextFactory.create(req, "agent_drawer");

        RoutingRequirement analystReq = analyzer.analyze(analystCtx);
        RoutingRequirement drawerReq = analyzer.analyze(drawerCtx);

        assertEquals(TaskType.DRAWIO_GENERATION, analystReq.taskType());
        assertEquals(TaskType.DRAWIO_GENERATION, drawerReq.taskType());

        // Drawer must have higher structured output & tool calling demands
        assertTrue(drawerReq.structuredOutputRequired() > analystReq.structuredOutputRequired(),
                "Drawer structured output demand must be higher than Analyst");
        assertTrue(drawerReq.toolCallingRequired() > analystReq.toolCallingRequired(),
                "Drawer tool calling demand must be higher than Analyst");
        assertTrue(drawerReq.expectedOutputTokens() > analystReq.expectedOutputTokens(),
                "Drawer expected output token budget must be higher than Analyst");
    }

    // =========================================================================
    // Reviewer Policy
    // =========================================================================

    @Test
    void reviewerPolicy_exhibitsHighReasoningAndValidationDemands() {
        RoutingContext ctx = contextFactory.create(request(userContent("审核并校验该图表 XML 结构")), "agent_reviewer");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertTrue(req.reasoningRequired() >= 80);
        assertTrue(req.structuredOutputRequired() >= 90);
        assertTrue(req.codingRequired() >= 65);
    }

    // =========================================================================
    // Vision Detection: Multimodal vs Pure Text vs Historical Isolation (Phase 3.1)
    // =========================================================================

    @Test
    void visionDetection_pureTextWithKeyword_doesNotTriggerVisionRequired() {
        RoutingContext ctx = contextFactory.create(request(userContent("帮我设计一个处理图片生成的 Agent 文档")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertFalse(req.visionRequired(), "Text containing '图片' keyword must NOT trigger visionRequired");
    }

    @Test
    void visionDetection_currentUserImage_triggersVisionRequired() {
        Part imagePart = Part.builder()
                .inlineData(Blob.builder().mimeType("image/png").data(new byte[]{1, 2, 3}).build())
                .build();
        Content multimodalContent = Content.builder().role("user").parts(List.of(imagePart, Part.fromText("结合这张图片分析"))).build();
        LlmRequest req = request(multimodalContent);

        RoutingContext ctx = contextFactory.create(req, "agent_analyst");
        RoutingRequirement reqResult = analyzer.analyze(ctx);

        assertTrue(reqResult.visionRequired(), "Current-turn multimodal image part must trigger visionRequired");
    }

    @Test
    void visionDetection_historyImage_currentTextOnly_shouldNotRequireVision() {
        // Turn 1: User sent image -> Assistant answered
        Part historyImage = Part.builder()
                .inlineData(Blob.builder().mimeType("image/png").data(new byte[]{1, 2, 3}).build())
                .build();
        Content turn1User = Content.builder().role("user").parts(List.of(historyImage, Part.fromText("分析图片"))).build();
        Content turn1Assistant = Content.builder().role("model").parts(List.of(Part.fromText("图片分析结果：架构图包含网关与服务层"))).build();

        // Turn 2: Current user sends pure text "修改标题"
        Content turn2User = Content.builder().role("user").parts(List.of(Part.fromText("修改标题为用户流程"))).build();

        LlmRequest req = request(turn1User, turn1Assistant, turn2User);

        RoutingContext ctx = contextFactory.create(req, "agent_analyst");
        RoutingRequirement reqResult = analyzer.analyze(ctx);

        assertEquals(TaskType.SIMPLE_EDIT, reqResult.taskType());
        assertFalse(reqResult.visionRequired(), "Historical image must NOT contaminate current pure-text turn");
    }

    @Test
    void visionDetection_assistantHistoricalImage_shouldNotRequireVision() {
        // Historical assistant generated/attached image
        Part assistantImg = Part.builder()
                .inlineData(Blob.builder().mimeType("image/jpeg").data(new byte[]{4, 5}).build())
                .build();
        Content turn1User = userContent("生成一张架构图");
        Content turn1Assistant = Content.builder().role("model").parts(List.of(assistantImg, Part.fromText("已生成图表"))).build();
        Content turn2User = userContent("修改标题");

        LlmRequest req = request(turn1User, turn1Assistant, turn2User);

        RoutingContext ctx = contextFactory.create(req, "agent_analyst");
        RoutingRequirement reqResult = analyzer.analyze(ctx);

        assertFalse(reqResult.visionRequired(), "Historical assistant image must NOT contaminate current turn");
    }

    // =========================================================================
    // Context / Vision Dual Decoupling (Phase 3.1 Comprehensive Regression)
    // =========================================================================

    @Test
    void contextAndVisionDecoupling_comprehensiveRegression() {
        // Case A: Short text only, no history
        LlmRequest reqA = request(userContent("修改标题为系统概览"));
        RoutingContext ctxA = contextFactory.create(reqA, "agent_analyst");
        RoutingRequirement reqA_res = analyzer.analyze(ctxA);

        // Case B: Same short text, but with massive history and historical image
        Part historyImage = Part.builder()
                .inlineData(Blob.builder().mimeType("image/png").data(new byte[]{1, 2, 3}).build())
                .build();
        Content turn1User = Content.builder().role("user").parts(List.of(historyImage, Part.fromText("历史长文本描述：".repeat(500)))).build();
        Content turn1Model = assistantContent("历史模型详细输出：".repeat(800));
        Content turn2User = userContent("修改标题为系统概览");

        LlmRequest reqB = request(turn1User, turn1Model, turn2User);
        RoutingContext ctxB = contextFactory.create(reqB, "agent_analyst");
        RoutingRequirement reqB_res = analyzer.analyze(ctxB);

        // Assertions:
        // 1. Task type and reasoning requirements are identical
        assertEquals(TaskType.SIMPLE_EDIT, reqA_res.taskType());
        assertEquals(TaskType.SIMPLE_EDIT, reqB_res.taskType());
        assertEquals(reqA_res.reasoningRequired(), reqB_res.reasoningRequired());

        // 2. Neither requires vision
        assertFalse(reqA_res.visionRequired());
        assertFalse(reqB_res.visionRequired(), "Case B with historical image must NOT require vision");

        // 3. But minContextWindowTokens MUST strictly reflect the larger historical context
        assertTrue(reqB_res.minContextWindowTokens() > reqA_res.minContextWindowTokens(),
                "minContextWindowTokens in Case B must be strictly larger than Case A");
    }

    // =========================================================================
    // Expected Output Budget
    // =========================================================================

    @Test
    void expectedOutputBudget_simpleEditLessThanDrawioGeneration() {
        RoutingContext editCtx = contextFactory.create(request(userContent("修改标题")), "agent_analyst");
        RoutingContext drawCtx = contextFactory.create(request(userContent("画登录流程图")), "agent_drawer");

        RoutingRequirement editReq = analyzer.analyze(editCtx);
        RoutingRequirement drawReq = analyzer.analyze(drawCtx);

        assertTrue(editReq.expectedOutputTokens() < drawReq.expectedOutputTokens());
    }

    // =========================================================================
    // Score Range Clamping (0 ~ 100)
    // =========================================================================

    @Test
    void scoreRange_allScoresClampedBetweenZeroAndHundred() {
        RoutingContext ctx = contextFactory.create(request(userContent("画一个极其复杂的跨系统高并发分布式微服务流程图并写代码")), "agent_drawer");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertTrue(req.reasoningRequired() >= 0 && req.reasoningRequired() <= 100);
        assertTrue(req.instructionFollowingRequired() >= 0 && req.instructionFollowingRequired() <= 100);
        assertTrue(req.codingRequired() >= 0 && req.codingRequired() <= 100);
        assertTrue(req.structuredOutputRequired() >= 0 && req.structuredOutputRequired() <= 100);
        assertTrue(req.toolCallingRequired() >= 0 && req.toolCallingRequired() <= 100);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LlmRequest request(Content... contents) {
        return LlmRequest.builder().model("test").contents(List.of(contents)).build();
    }

    private static Content userContent(String text) {
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    private static Content assistantContent(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }
}
