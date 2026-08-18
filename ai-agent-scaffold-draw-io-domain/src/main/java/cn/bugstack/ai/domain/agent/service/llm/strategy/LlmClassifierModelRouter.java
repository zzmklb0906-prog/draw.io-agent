package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.RoutingTextInput;
import com.google.adk.models.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Heuristic intent-complexity classifier router (legacy Tier 2).
 *
 * <p><strong>IMPORTANT — Phase 1 note:</strong>
 * The class name "LlmClassifier" and method name {@code evaluateWithSlm()} are legacy
 * misnomers. This class does <em>NOT</em> call an SLM (Small Language Model) or any LLM.
 * It is a rule-based heuristic classifier using character-length thresholds and keyword
 * matching — functionally equivalent to a simplified version of
 * {@link SemanticVectorModelRouter}.
 *
 * <p>This class will be renamed and refactored in a future phase once a real
 * SLM/LLM-based intent classifier is integrated. At that point the SLM output
 * should produce capability requirement scores (not direct model names).
 *
 * <p><strong>Phase 1 fix:</strong> Now receives {@link RoutingTextInput} so that
 * {@code evaluateWithSlm()} only sees the latest user message, not the full
 * conversation history.
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
        // Phase 1 fix: extract only the latest user message for complexity analysis.
        // Before: String.valueOf(request.contents()) used the entire conversation history.
        RoutingTextInput input = buildRoutingInput(request);
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
     * Rule-based heuristic classifier.
     *
     * <p><strong>NOT an SLM/LLM call.</strong> The method name is a legacy misnomer.
     * This is a pure if-else heuristic operating on {@code latestUserText} only
     * (Phase 1 fix: was previously receiving the full history string).
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RoutingTextInput buildRoutingInput(LlmRequest request) {
        if (request == null || request.contents() == null || request.contents().isEmpty()) {
            return RoutingTextInput.empty();
        }
        var contents = request.contents();
        String latestUserText = "";
        int totalChars = 0;

        for (int i = contents.size() - 1; i >= 0; i--) {
            var content = contents.get(i);
            if (latestUserText.isEmpty()
                    && content.role().map(r -> "user".equalsIgnoreCase(r.trim())).orElse(false)) {
                latestUserText = content.text() != null ? content.text() : "";
            }
        }
        for (var content : contents) {
            totalChars += (content.text() != null ? content.text().length() : 0);
        }
        return new RoutingTextInput(latestUserText, totalChars);
    }

    private record ClassifierResult(int complexity, String reason) {}
}
