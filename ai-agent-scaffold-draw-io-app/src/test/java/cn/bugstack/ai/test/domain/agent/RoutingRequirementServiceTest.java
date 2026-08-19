package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.*;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoutingRequirementService}.
 */
class RoutingRequirementServiceTest {

    private RoutingContextFactory contextFactory;
    private RoutingRequirementService requirementService;

    @BeforeEach
    void setUp() {
        LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();
        HeuristicContextTokenEstimator tokenEstimator = new HeuristicContextTokenEstimator(extractor);
        this.contextFactory = new RoutingContextFactory(extractor, tokenEstimator);

        TaskTypeDetector detector = new TaskTypeDetector();
        CurrentTurnVisionDetector visionDetector = new CurrentTurnVisionDetector(extractor);
        List<AgentRequirementPolicy> policies = List.of(new AnalystAgentRequirementPolicy(), new DrawerAgentRequirementPolicy());
        RuleBasedRoutingRequirementAnalyzer analyzer = new RuleBasedRoutingRequirementAnalyzer(detector, visionDetector, policies);

        this.requirementService = new RoutingRequirementService(analyzer);
    }

    @Test
    void tryAnalyze_successfulContext_returnsRequirement() {
        LlmRequest req = LlmRequest.builder()
                .model("test")
                .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText("画登录流程图"))).build()))
                .build();
        RoutingContext ctx = contextFactory.create(req, "agent_drawer");

        Optional<RoutingRequirement> requirement = requirementService.tryAnalyze(ctx);

        assertTrue(requirement.isPresent());
        assertEquals(TaskType.DRAWIO_GENERATION, requirement.get().taskType());
    }

    @Test
    void analyzeDetailed_nullContext_returnsFailure() {
        RoutingRequirementService.RequirementAnalysisResult result = requirementService.analyzeDetailed(null);

        assertFalse(result.isSuccess());
        assertEquals(RoutingRequirementService.RequirementAnalysisResult.Status.FAILED, result.status());
        assertNull(result.requirement());
        assertNotNull(result.errorMessage());
    }
}
