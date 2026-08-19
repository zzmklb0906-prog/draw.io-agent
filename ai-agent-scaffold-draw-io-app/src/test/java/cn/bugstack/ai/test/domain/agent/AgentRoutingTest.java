package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.agent.routing.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9 — Agent Capability Routing & Agent Registry Tests (Case 1 to Case 6)
 */
@DisplayName("Phase 9 Agent Capability Routing & Agent Registry Tests")
public class AgentRoutingTest {

    private DefaultAgentRegistry registry;
    private RuleBasedAgentRequirementAnalyzer analyzer;
    private AgentSelector selector;
    private DefaultAgentRankingEngine rankingEngine;
    private AgentRoutingService routingService;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        analyzer = new RuleBasedAgentRequirementAnalyzer();
        selector = new AgentSelector(registry);
        rankingEngine = new DefaultAgentRankingEngine();
        routingService = new AgentRoutingService(analyzer, selector, rankingEngine);
    }

    // =========================================================================
    // Case 1: Draw.io Diagramming Request
    // =========================================================================
    @Test
    @DisplayName("Case 1: 绘图意图请求 - 验证自然语言请求被路由至 drawio-agent")
    void case1_drawingRequestRoutedToDrawioAgent() {
        String prompt = "请帮我绘制一个微信扫码登录的泳道时序图";

        AgentDecision decision = routingService.route(prompt);

        assertTrue(decision.isFound());
        assertEquals("drawio-agent", decision.selectedAgentId());
        assertNotNull(decision.reason());
    }

    // =========================================================================
    // Case 2: Code Generation Request
    // =========================================================================
    @Test
    @DisplayName("Case 2: 编程意图请求 - 验证代码编写需求被路由至 coding-agent")
    void case2_codeGenerationRequestRoutedToCodingAgent() {
        String prompt = "请用 Java 编写一段基于 Redis 分布式锁的实现代码并写单元测试";

        AgentDecision decision = routingService.route(prompt);

        assertTrue(decision.isFound());
        assertEquals("coding-agent", decision.selectedAgentId());
    }

    // =========================================================================
    // Case 3: Document Analysis Request
    // =========================================================================
    @Test
    @DisplayName("Case 3: 文档分析意图请求 - 验证长文阅读与总结需求被路由至 document-agent")
    void case3_documentAnalysisRequestRoutedToDocumentAgent() {
        String prompt = "请总结这篇微服务治理方案的技术文档要点并做排版";

        AgentDecision decision = routingService.route(prompt);

        assertTrue(decision.isFound());
        assertEquals("document-agent", decision.selectedAgentId());
    }

    // =========================================================================
    // Case 4: Multiple Agents Match & Ranking
    // =========================================================================
    @Test
    @DisplayName("Case 4: 多个候选 Agent 匹配 - 验证 AgentRankingEngine 精确打分并输出备选 Agent")
    void case4_multipleAgentsMatchingAndRanking() {
        // A prompt touching both data analysis and general chat
        String prompt = "统计数据并分析指标趋势，生成性能报表";

        AgentDecision decision = routingService.route(prompt);

        assertTrue(decision.isFound());
        assertEquals("data-analyst-agent", decision.selectedAgentId());
        assertNotNull(decision.backupAgents());
    }

    // =========================================================================
    // Case 5: No Agent Matches
    // =========================================================================
    @Test
    @DisplayName("Case 5: 无合适 Agent 匹配 - 验证安全返回 notFound 优雅兜底")
    void case5_noAgentMatchGracefulFallback() {
        // Registry containing zero agents
        AgentRegistry emptyRegistry = new AgentRegistry() {
            public java.util.List<AgentProfile> getAgents() { return java.util.List.of(); }
            public java.util.Optional<AgentProfile> find(String id) { return java.util.Optional.empty(); }
            public java.util.List<AgentProfile> getEnabledAgents() { return java.util.List.of(); }
            public void registerAgent(AgentProfile p) {}
        };

        AgentRoutingService customService = new AgentRoutingService(analyzer, new AgentSelector(emptyRegistry), rankingEngine);

        AgentDecision decision = customService.route("未知超自然请求");

        assertFalse(decision.isFound());
        assertNull(decision.selectedAgentId());
        assertTrue(decision.reason().contains("No suitable Agent"));
    }

    // =========================================================================
    // Case 6: Disabled Agent Is Ignored
    // =========================================================================
    @Test
    @DisplayName("Case 6: 禁用 Agent 过滤 - 验证 enabled=false 的 Agent 绝不被选中")
    void case6_disabledAgentNeverSelected() {
        // Register a disabled specialized agent
        AgentProfile disabledAgent = new AgentProfile(
                "disabled-specialist",
                "Disabled Specialist Agent",
                "Disabled specialized agent",
                Set.of(AgentCapability.DRAWING),
                Set.of(TaskType.DRAWIO_GENERATION),
                Set.of(),
                false // disabled
        );
        registry.registerAgent(disabledAgent);

        // Registry should still route to the active drawio-agent, not the disabled one
        AgentDecision decision = routingService.route("画个架构图");

        assertTrue(decision.isFound());
        assertEquals("drawio-agent", decision.selectedAgentId());
        assertFalse(decision.backupAgents().contains("disabled-specialist"));
    }
}
