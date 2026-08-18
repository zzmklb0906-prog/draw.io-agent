package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.ContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoutingContextFactory} and {@link HeuristicContextTokenEstimator} (Cases 1 - 9).
 */
class RoutingContextFactoryTest {

    private final LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
    private final ContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
    private final RoutingContextFactory factory = new RoutingContextFactory(extractor, tokenEstimator);

    @Test
    void case1_nullRequest_handlesSafely() {
        RoutingContext ctx = factory.create(null, "agent_analyst");
        assertNotNull(ctx);
        assertEquals("", ctx.latestUserText());
        assertEquals(0, ctx.totalContextChars());
        assertEquals(0L, ctx.estimatedContextTokens());
        assertEquals("agent_analyst", ctx.agentName());
        assertEquals("UNKNOWN", ctx.workflowStage());
        assertFalse(ctx.explicitModel());
    }

    @Test
    void case2_emptyContents_handlesSafely() {
        LlmRequest req = LlmRequest.builder().model("test").contents(List.of()).build();
        RoutingContext ctx = factory.create(req, "agent_drawer");
        assertEquals("", ctx.latestUserText());
        assertEquals(0, ctx.totalContextChars());
        assertEquals(0L, ctx.estimatedContextTokens());
    }

    @Test
    void case3_extractsLastUserMessage() {
        LlmRequest req = request(
                userContent("第一轮复杂架构设计"),
                assistantContent("模型回复"),
                userContent("修改标题为登录")
        );
        RoutingContext ctx = factory.create(req, "agent_analyst");
        assertEquals("修改标题为登录", ctx.latestUserText());
    }

    @Test
    void case4_multiTurnContext_preservedCorrectly() {
        LlmRequest req = request(
                userContent("问题一"),
                assistantContent("回答一"),
                userContent("问题二")
        );
        RoutingContext ctx = factory.create(req, "agent_drawer");
        assertEquals("问题二", ctx.latestUserText());
        assertTrue(ctx.totalContextChars() >= 9);
    }

    @Test
    void case5_agentName_preserved() {
        LlmRequest req = request(userContent("你好"));
        RoutingContext ctx = factory.create(req, "  agent_drawer  ");
        assertEquals("agent_drawer", ctx.agentName());

        RoutingContext nullAgentCtx = factory.create(req, null);
        assertEquals("unknown", nullAgentCtx.agentName());
    }

    @Test
    void case6_workflowStage_preserved() {
        LlmRequest req = request(userContent("你好"));
        RoutingContext ctx = factory.create(req, "agent_drawer", "EXECUTION_PHASE", false, null);
        assertEquals("EXECUTION_PHASE", ctx.workflowStage());
    }

    @Test
    void case7_explicitModelMetadata_preserved() {
        LlmRequest req = request(userContent("你好"));
        RoutingContext ctx = factory.create(req, "agent_drawer", "UNKNOWN", true, "qwen3.8-max");
        assertTrue(ctx.explicitModel());
        assertEquals("qwen3.8-max", ctx.explicitModelName());
    }

    @Test
    void case8_totalContextChars_greaterThanLatestUserText() {
        LlmRequest req = request(
                userContent("A".repeat(1000)),
                assistantContent("B".repeat(2000)),
                userContent("C".repeat(10))
        );
        RoutingContext ctx = factory.create(req, "agent_drawer");
        assertEquals(10, ctx.latestUserText().length());
        assertEquals(3010, ctx.totalContextChars());
        assertTrue(ctx.totalContextChars() > ctx.latestUserText().length());
    }

    @Test
    void case9_estimatedContextTokens_isNonNegative() {
        LlmRequest req = request(userContent("这是一个长文本测试".repeat(50)));
        RoutingContext ctx = factory.create(req, "agent_analyst");
        assertTrue(ctx.estimatedContextTokens() > 0);
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
