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

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelRoutingServiceTest {

    private final RuleBasedModelRouter ruleRouter = new RuleBasedModelRouter();
    private final SemanticVectorModelRouter semanticRouter = new SemanticVectorModelRouter();
    private final LlmClassifierModelRouter classifierRouter = new LlmClassifierModelRouter(semanticRouter);
    private final CompositeModelRouter compositeRouter = new CompositeModelRouter(semanticRouter, classifierRouter, ruleRouter);

    private final ModelRoutingService router = new ModelRoutingService(
            true, "composite", "fast", "balanced", "reasoning",
            List.of(ruleRouter, semanticRouter, classifierRouter, compositeRouter)
    );

    @Test
    void routesSimpleFormattingToFastModel() {
        assertEquals("fast", router.route(request("请把这段内容进行摘要和格式整理")).model());
    }

    @Test
    void routesArchitectureAnalysisToReasoningModel() {
        assertEquals("reasoning", router.route(request("请完成跨模块架构分析并检查状态机调用链")).model());
    }

    @Test
    void routesNormalTaskToBalancedModel() {
        assertEquals("balanced", router.route(request("画一个用户登录流程图")).model());
    }

    private LlmRequest request(String text) {
        return LlmRequest.builder().model("default").contents(List.of(Content.fromParts(Part.fromText(text)))).build();
    }
}
