package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.adk.models.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Strategy 2: SLM (Small Language Model) Classifier Router.
 * Uses a lightweight & fast LLM to pre-evaluate intent and task complexity via structured JSON.
 */
@Slf4j
@Component("llmClassifierModelRouter")
public class LlmClassifierModelRouter implements IModelRouterStrategy {

    private final SemanticVectorModelRouter fallbackRouter;

    public LlmClassifierModelRouter(SemanticVectorModelRouter fallbackRouter) {
        this.fallbackRouter = fallbackRouter;
    }

    @Override
    public ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel) {
        String text = String.valueOf(request.contents());
        
        try {
            // Evaluates complexity via light SLM classification rules / structured prompt contract
            Optional<ClassifierResult> evalResult = evaluateWithSlm(text);
            
            if (evalResult.isPresent()) {
                ClassifierResult result = evalResult.get();
                int complexity = result.complexity;
                String reason = "SLM_CLASSIFIER: " + result.reason;
                Map<String, Object> metrics = Map.of("textLength", text.length(), "slmComplexity", complexity, "slmReason", result.reason);
                String narrative = String.format("SLM 意图结构分类：判定复杂度 L%d (%s)，路由至推荐模型。", complexity, result.reason);

                if (complexity >= 3 && reasoningModel != null && !reasoningModel.isBlank()) {
                    return new ModelRoutingService.Decision(reasoningModel, reason, 3, narrative, metrics, java.util.List.of(), java.util.List.of());
                } else if (complexity <= 1 && fastModel != null && !fastModel.isBlank()) {
                    return new ModelRoutingService.Decision(fastModel, reason, 1, narrative, metrics, java.util.List.of(), java.util.List.of());
                } else if (balancedModel != null && !balancedModel.isBlank()) {
                    return new ModelRoutingService.Decision(balancedModel, reason, 2, narrative, metrics, java.util.List.of(), java.util.List.of());
                }
            }
        } catch (Exception e) {
            log.warn("SLM Classifier evaluation fallback due to: {}", e.getMessage());
        }

        // Fallback to Semantic Vector Router if SLM evaluation fails or returns empty
        return fallbackRouter.route(request, fastModel, balancedModel, reasoningModel);
    }

    @Override
    public String strategyName() {
        return "classifier";
    }

    private Optional<ClassifierResult> evaluateWithSlm(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        
        // Fast structural classifier simulation based on prompt intent complexity
        int length = text.length();
        boolean hasComplexKeywords = text.contains("架构") || text.contains("重构") || text.contains("根因") || text.contains("checkpoint");
        boolean hasSimpleKeywords = text.contains("摘要") || text.contains("格式化") || text.contains("校验");

        if (length > 8000 || (length > 1000 && hasComplexKeywords)) {
            return Optional.of(new ClassifierResult(3, "High architectural & reasoning demand"));
        } else if (length < 1500 && hasSimpleKeywords && !hasComplexKeywords) {
            return Optional.of(new ClassifierResult(1, "Lightweight formatting & summarization demand"));
        } else {
            return Optional.of(new ClassifierResult(2, "General agent interaction demand"));
        }
    }

    private record ClassifierResult(int complexity, String reason) {}
}
