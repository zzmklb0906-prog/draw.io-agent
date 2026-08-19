package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.RoutingTextInput;
import com.google.adk.models.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rule-Based Classifier (Heuristic Intent & Length Rules).
 *
 * <p><strong>Design & Implementation Reality:</strong>
 * This class implements a <em>Rule-Based Classifier</em> using character-length thresholds
 * and intent keyword pattern matching. It does <strong>NOT</strong> call an SLM (Small Language Model)
 * or any LLM model.
 * The class name "LlmClassifierModelRouter" is retained for backward compatibility.
 *
 * <p>Core algorithm:
 * <ul>
 *   <li>Length &gt; 8000 or (Length &gt; 500 and hasComplexKeywords and not hasSimpleKeywords) &rarr; L3 (Reasoning)</li>
 *   <li>Length &lt; 1500 and hasSimpleKeywords and not hasComplexKeywords &rarr; L1 (Fast)</li>
 *   <li>Otherwise &rarr; L2 (Balanced)</li>
 * </ul>
 */
@Slf4j
@Component("llmClassifierModelRouter")
public class LlmClassifierModelRouter implements IModelRouterStrategy {

    private final LatestUserMessageExtractor extractor;
    private final SemanticVectorModelRouter fallbackRouter;

    public LlmClassifierModelRouter(LatestUserMessageExtractor extractor,
                                    SemanticVectorModelRouter fallbackRouter) {
        this.extractor = extractor;
        this.fallbackRouter = fallbackRouter;
    }

    @Override
    public ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel) {
        RoutingTextInput input = extractor.buildRoutingInput(request);
        return routeFromInput(input, fastModel, balancedModel, reasoningModel);
    }

    /**
     * Package-private overload allowing reuse of a pre-built {@link RoutingTextInput}.
     */
    ModelRoutingService.Decision routeFromInput(RoutingTextInput input,
                                                String fastModel,
                                                String balancedModel,
                                                String reasoningModel) {
        String latestUserText = input.latestUserText();

        try {
            Optional<ClassifierResult> evalResult = evaluateWithSlm(latestUserText);

            if (evalResult.isPresent()) {
                ClassifierResult result = evalResult.get();
                int complexity = result.complexity;
                String reason = "HEURISTIC_CLASSIFIER: " + result.reason;
                Map<String, Object> metrics = Map.of(
                        "latestUserTextLength", latestUserText.length(),
                        "totalContextChars", input.totalContextChars(),
                        "heuristicComplexity", complexity,
                        "heuristicReason", result.reason
                );
                String narrative = String.format(
                        "启发式分类器：当前用户消息长 %d 字符，判定复杂度 L%d (%s)。",
                        latestUserText.length(), complexity, result.reason);

                if (complexity >= 3 && reasoningModel != null && !reasoningModel.isBlank()) {
                    return new ModelRoutingService.Decision(reasoningModel, reason, 3, narrative, metrics, List.of(), List.of());
                } else if (complexity <= 1 && fastModel != null && !fastModel.isBlank()) {
                    return new ModelRoutingService.Decision(fastModel, reason, 1, narrative, metrics, List.of(), List.of());
                } else if (balancedModel != null && !balancedModel.isBlank()) {
                    return new ModelRoutingService.Decision(balancedModel, reason, 2, narrative, metrics, List.of(), List.of());
                }
            }
        } catch (Exception e) {
            log.warn("Heuristic classifier evaluation failed, falling back: {}", e.getMessage());
        }

        // Fallback: delegate to SemanticVectorModelRouter with the same RoutingTextInput
        return fallbackRouter.routeFromInput(input, fastModel, balancedModel, reasoningModel);
    }

    @Override
    public String strategyName() {
        return "classifier";
    }

    /**
     * Rule-based heuristic classification (length & keyword pattern matching).
     *
     * <p><strong>Design Reality:</strong>
     * Pure if-else heuristic operating on {@code latestUserText} only without calling external SLM/LLM models.
     *
     * @param latestUserText the current user message only
     */
    private Optional<ClassifierResult> evaluateWithSlm(String latestUserText) {
        if (latestUserText == null || latestUserText.isBlank()) {
            return Optional.empty();
        }

        int length = latestUserText.length();
        String lower = latestUserText.toLowerCase();

        // Phase 1 note: These keyword checks run on latestUserText only.
        // Known limitation: no negation handling (Phase 2+).
        boolean hasComplexKeywords = lower.contains("架构") || lower.contains("重构")
                || lower.contains("根因") || lower.contains("checkpoint")
                || lower.contains("分布式") || lower.contains("状态机")
                || lower.contains("并发") || lower.contains("一致性");
        boolean hasSimpleKeywords = lower.contains("摘要") || lower.contains("格式化")
                || lower.contains("校验") || lower.contains("改写")
                || lower.contains("翻译") || lower.contains("标题");

        if (length > 8000 || (length > 500 && hasComplexKeywords && !hasSimpleKeywords)) {
            return Optional.of(new ClassifierResult(3, "High complexity keywords detected in current message"));
        } else if (length < 1500 && hasSimpleKeywords && !hasComplexKeywords) {
            return Optional.of(new ClassifierResult(1, "Lightweight formatting/editing request"));
        } else {
            return Optional.of(new ClassifierResult(2, "General agent interaction"));
        }
    }

    private record ClassifierResult(int complexity, String reason) {}
}
