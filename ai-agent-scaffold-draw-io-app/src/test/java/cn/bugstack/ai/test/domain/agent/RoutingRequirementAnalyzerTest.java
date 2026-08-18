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
 * Unit tests for {@link RuleBasedRoutingRequirementAnalyzer} (Phase 3 Requirement Analysis).
 *
 * <p>Validates task detection, agent-aware requirement adjustments, context/complexity separation,
 * vision detection, score clamping, and zero dependency on ModelCatalogService.</p>
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
        List<AgentRequirementPolicy> policies = List.of(
                new AnalystAgentRequirementPolicy(),
                new DrawerAgentRequirementPolicy(),
                new ReviewerAgentRequirementPolicy()
        );
        // Note: Zero dependency on ModelCatalogService
        this.analyzer = new RuleBasedRoutingRequirementAnalyzer(detector, policies);
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
    // Vision Detection: Multimodal vs Pure Text
    // =========================================================================

    @Test
    void visionDetection_pureTextWithKeyword_doesNotTriggerVisionRequired() {
        RoutingContext ctx = contextFactory.create(request(userContent("帮我设计一个处理图片生成的 Agent 文档")), "agent_analyst");
        RoutingRequirement req = analyzer.analyze(ctx);

        assertFalse(req.visionRequired(), "Text containing '图片' keyword must NOT trigger visionRequired");
    }

    @Test
    void visionDetection_multimodalInlineData_triggersVisionRequired() {
        Part imagePart = Part.builder()
                .inlineData(Blob.builder().mimeType("image/png").data(new byte[]{1, 2, 3}).build())
                .build();
        Content multimodalContent = Content.builder().role("user").parts(List.of(imagePart, Part.fromText("分析图片"))).build();
        LlmRequest req = LlmRequest.builder().model("test").contents(List.of(multimodalContent)).build();

        RoutingContext ctx = contextFactory.create(req, "agent_analyst");
        RoutingRequirement reqResult = analyzer.analyze(ctx);

        assertTrue(reqResult.visionRequired(), "Multimodal image part must trigger visionRequired");
    }

    // =========================================================================
    // Context / Complexity Decoupling
    // =========================================================================

    @Test
    void contextDecoupling_longHistoryDoesNotEscalateTaskComplexity() {
        // Very large history context (20,000+ chars)
        String hugeHistoryUser = "历史大量系统需求描述：".repeat(1000);
        String hugeHistoryModel = "历史模型详细设计输出：".repeat(1500);

        LlmRequest longHistoryReq = LlmRequest.builder()
                .model("test")
                .contents(List.of(
                        userContent(hugeHistoryUser),
                        assistantContent(hugeHistoryModel),
                        userContent("修改标题为系统概览")
                ))
                .build();

        RoutingContext shortContext = contextFactory.create(request(userContent("修改标题为系统概览")), "agent_analyst");
        RoutingContext longContext = contextFactory.create(longHistoryReq, "agent_analyst");

        RoutingRequirement shortReq = analyzer.analyze(shortContext);
        RoutingRequirement longReq = analyzer.analyze(longContext);

        // Task complexity remains SIMPLE_EDIT and reasoningRequired is identical
        assertEquals(TaskType.SIMPLE_EDIT, longReq.taskType());
        assertEquals(shortReq.reasoningRequired(), longReq.reasoningRequired(),
                "Long conversation history must NOT escalate current task reasoningRequired");

        // But minContextWindowTokens MUST reflect the full context requirement
        assertTrue(longReq.minContextWindowTokens() > shortReq.minContextWindowTokens(),
                "minContextWindowTokens must increase with larger historical context");
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
