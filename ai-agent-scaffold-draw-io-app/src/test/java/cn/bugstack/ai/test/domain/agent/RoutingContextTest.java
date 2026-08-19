package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.ContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 — RoutingContext Domain Model and Factory Tests
 */
@DisplayName("Phase 1 RoutingContext & Requirement Foundation Tests")
public class RoutingContextTest {

    private final LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
    private final ContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
    private final RoutingContextFactory factory = new RoutingContextFactory(extractor, tokenEstimator);

    // =========================================================================
    // Case 1: Standard single-turn user request
    // =========================================================================
    @Test
    @DisplayName("Case 1: 正常用户请求 - 验证 latestUserMessage / latestUserText 正确提取")
    void case1_standardUserRequest() {
        String prompt = "绘制一个微服务订单创建状态机流程图";
        LlmRequest request = buildRequest(userContent(prompt));

        RoutingContext context = factory.create(request);

        assertNotNull(context);
        assertEquals(prompt, context.latestUserMessage());
        assertEquals(prompt, context.latestUserText());
        assertEquals(prompt.length(), context.inputLength());
        assertTrue(context.totalContextChars() >= prompt.length());
        assertEquals("unknown", context.agentName());
        assertEquals("UNKNOWN", context.workflowStage());
        assertFalse(context.explicitModel());
        assertNull(context.explicitModelName());
        assertFalse(context.hasToolContext());
    }

    // =========================================================================
    // Case 2: Multi-turn conversation
    // =========================================================================
    @Test
    @DisplayName("Case 2: 多轮消息 - 验证仅提取最后一轮用户消息，历史长度独立记录")
    void case2_multiTurnMessages() {
        LlmRequest request = buildRequest(
                userContent("第一轮：请设计高可用分布式架构方案并分析并发一致性问题"),
                assistantContent("方案如下：采用 Raft 协议保证多副本强一致性..."),
                userContent("第二轮：把标题改为登录流程图")
        );

        RoutingContext context = factory.create(request, "agent_drawer");

        assertNotNull(context);
        assertEquals("第二轮：把标题改为登录流程图", context.latestUserMessage());
        assertEquals("第二轮：把标题改为登录流程图", context.latestUserText());
        assertEquals("第二轮：把标题改为登录流程图".length(), context.inputLength());
        assertTrue(context.totalContextChars() > context.inputLength());
        assertEquals("agent_drawer", context.agentName());
    }

    // =========================================================================
    // Case 3: Explicit model override
    // =========================================================================
    @Test
    @DisplayName("Case 3: 显式模型 - 验证 explicitModel 与 explicitModelName 准确保存")
    void case3_explicitModelOverride() {
        String prompt = "普通画图任务";
        LlmRequest request = buildRequest(userContent(prompt));
        String customModel = "deepseek-reasoner-v3";

        RoutingContext context = factory.create(request, "agent_drawer", "EXECUTION", true, customModel);

        assertNotNull(context);
        assertTrue(context.explicitModel());
        assertEquals(customModel, context.explicitModelName());
        assertEquals("EXECUTION", context.workflowStage());
        assertEquals("agent_drawer", context.agentName());
        assertEquals(prompt, context.latestUserMessage());
    }

    // =========================================================================
    // Case 4: Empty / Null request safe handling
    // =========================================================================
    @Test
    @DisplayName("Case 4: 空请求 - 验证防御性安全处理，不产生 NPE")
    void case4_emptyRequestSafeHandling() {
        RoutingContext nullContext = factory.create(null);
        assertNotNull(nullContext);
        assertEquals("", nullContext.latestUserMessage());
        assertEquals(0, nullContext.inputLength());
        assertEquals(0, nullContext.totalContextChars());
        assertEquals(0L, nullContext.estimatedContextTokens());
        assertFalse(nullContext.hasToolContext());

        LlmRequest emptyRequest = LlmRequest.builder().model("default").contents(List.of()).build();
        RoutingContext emptyContext = factory.create(emptyRequest);
        assertNotNull(emptyContext);
        assertEquals("", emptyContext.latestUserMessage());
        assertEquals(0, emptyContext.inputLength());
        assertEquals(0, emptyContext.totalContextChars());
    }

    // =========================================================================
    // Case 5: Different Agent identity isolation
    // =========================================================================
    @Test
    @DisplayName("Case 5: 不同 Agent 隔离 - 验证 agentName 独立性")
    void case5_differentAgentIsolation() {
        LlmRequest request = buildRequest(userContent("生成序列图"));

        RoutingContext drawioContext = factory.create(request, "drawio-agent");
        RoutingContext analysisContext = factory.create(request, "analysis-agent");
        RoutingContext blankAgentContext = factory.create(request, "   ");

        assertEquals("drawio-agent", drawioContext.agentName());
        assertEquals("analysis-agent", analysisContext.agentName());
        assertEquals("unknown", blankAgentContext.agentName());
    }

    // =========================================================================
    // Case 6: Tool context detection
    // =========================================================================
    @Test
    @DisplayName("Case 6: 工具上下文检测 - 验证 hasToolContext 准确识别")
    void case6_toolContextDetection() {
        Tool dummyTool = Tool.builder().build();
        GenerateContentConfig configWithTools = GenerateContentConfig.builder()
                .tools(List.of(dummyTool))
                .build();

        LlmRequest requestWithTools = LlmRequest.builder()
                .model("default")
                .contents(List.of(userContent("调用工具读取文件")))
                .config(configWithTools)
                .build();

        RoutingContext context = factory.create(requestWithTools, "agent_tool_user");
        assertTrue(context.hasToolContext());
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private static LlmRequest buildRequest(Content... contents) {
        return LlmRequest.builder()
                .model("default")
                .contents(List.of(contents))
                .build();
    }

    private static Content userContent(String text) {
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    private static Content assistantContent(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }
}
