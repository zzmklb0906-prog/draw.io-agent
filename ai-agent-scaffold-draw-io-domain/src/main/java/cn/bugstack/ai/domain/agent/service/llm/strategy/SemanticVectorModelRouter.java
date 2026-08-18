package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.RoutingTextInput;
import com.google.adk.models.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyword-density heuristic router (legacy).
 *
 * <p><strong>IMPORTANT — Phase 1 note:</strong>
 * This class is a <em>keyword-density heuristic</em>, NOT a real embedding / vector
 * semantic router. It does NOT use an Embedding model, vector representations,
 * cosine similarity, or a trained classifier. The name "SemanticVector" is a legacy
 * misnomer and will be addressed in a future refactoring phase.
 *
 * <p>Core algorithm:
 * <ol>
 *   <li>Scan {@code latestUserText} (Phase 1 fix: was the full conversation history) for
 *       domain keywords and accumulate a weighted score.</li>
 *   <li>Apply a Poisson-style saturation: {@code score = 1 - exp(-accumulatedWeight)}
 *       so that a single keyword cannot saturate to 1.0.</li>
 *   <li>Combine with a log-scaled context-length factor (using {@code totalContextChars}
 *       for window-budget awareness, NOT as a complexity driver).</li>
 *   <li>Compare to fixed thresholds to select L1 / L2 / L3 tier.</li>
 * </ol>
 *
 * <p>Known limitations (to be fixed in Phase 2+):
 * <ul>
 *   <li>Cannot detect negation ("不需要架构分析") — treats negated keywords the same as affirmed ones.</li>
 *   <li>Single keyword with weight 0.9 can push score above 0.45 threshold when combined with
 *       context-length factor — see threshold discussion in the refactor guide.</li>
 *   <li>Substring matching only — no synonym or semantic awareness.</li>
 * </ul>
 */
@Slf4j
@Component("semanticVectorModelRouter")
public class SemanticVectorModelRouter implements IModelRouterStrategy {

    // Keyword weights for high-complexity tasks
    private static final Map<String, Double> REASONING_KEYWORDS = new HashMap<>() {{
        put("架构", 0.9); put("architecture", 0.9);
        put("重构", 0.85); put("refactor", 0.85);
        put("根因", 0.88); put("root cause", 0.88);
        put("分布式", 0.85); put("distributed", 0.85);
        put("状态机", 0.9); put("state machine", 0.9);
        put("checkpoint", 0.88); put("一致性", 0.85);
        put("高可用", 0.8); put("并发", 0.8); put("concurrency", 0.8);
        put("性能瓶颈", 0.85); put("optimization", 0.8);
    }};

    // Keyword weights for lightweight tasks
    private static final Map<String, Double> LIGHTWEIGHT_KEYWORDS = new HashMap<>() {{
        put("摘要", 0.9); put("summary", 0.9);
        put("改写", 0.85); put("rewrite", 0.85);
        put("格式化", 0.88); put("format", 0.88);
        put("校验", 0.8); put("validate", 0.8);
        put("翻译", 0.85); put("translate", 0.85);
        put("提取", 0.8); put("extract", 0.8);
        put("纠错", 0.8); put("title", 0.8);
    }};

    // -------------------------------------------------------------------------
    // IModelRouterStrategy — primary entry point (called by CompositeModelRouter)
    // -------------------------------------------------------------------------

    @Override
    public ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel) {
        // Phase 1 fix: all keyword analysis now runs on latestUserText only.
        // totalContextChars is kept as a separate metric for context-window budget awareness.
        // Before: String.valueOf(request.contents()) fed the ENTIRE conversation history.
        RoutingTextInput input = buildRoutingInput(request);
        return routeFromInput(input, fastModel, balancedModel, reasoningModel);
    }

    /**
     * Package-private overload for CompositeModelRouter — allows passing a pre-built
     * RoutingTextInput to avoid redundant extraction.
     */
    ModelRoutingService.Decision routeFromInput(RoutingTextInput input,
                                                String fastModel,
                                                String balancedModel,
                                                String reasoningModel) {
        String latestUserText = input.latestUserText();
        String lower = latestUserText.toLowerCase();

        DensityResult reasoningResult = calculateDensity(lower, REASONING_KEYWORDS);
        DensityResult lightweightResult = calculateDensity(lower, LIGHTWEIGHT_KEYWORDS);

        double reasoningScore = reasoningResult.score;
        double lightweightScore = lightweightResult.score;

        // Context-length factor: uses totalContextChars to reflect window budget.
        // NOTE: this is a context-size indicator, NOT a measure of current task complexity.
        double logLengthFactor = Math.min(1.0, Math.log10(Math.max(1, input.totalContextChars())) / 4.5);
        double finalReasoningScore = (reasoningScore * 0.7) + (logLengthFactor * 0.3);

        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("latestUserTextLength", latestUserText.length());
        metrics.put("totalContextChars", input.totalContextChars());
        metrics.put("reasoningScore", Math.round(reasoningScore * 1000.0) / 1000.0);
        metrics.put("lightweightScore", Math.round(lightweightScore * 1000.0) / 1000.0);
        metrics.put("logLengthFactor", Math.round(logLengthFactor * 1000.0) / 1000.0);
        metrics.put("finalReasoningScore", Math.round(finalReasoningScore * 1000.0) / 1000.0);
        metrics.put("reasoningThreshold", 0.45);
        metrics.put("lightweightThreshold", 0.40);

        log.debug("Keyword-density heuristic: latestLen={}, contextLen={}, " +
                        "reasoningScore={}, finalReasoningScore={}, lightweightScore={}, " +
                        "matchedReasoning={}, matchedLightweight={}",
                latestUserText.length(), input.totalContextChars(),
                reasoningScore, finalReasoningScore, lightweightScore,
                reasoningResult.matchedKeywords, lightweightResult.matchedKeywords);

        if (finalReasoningScore > 0.45 && reasoningModel != null && !reasoningModel.isBlank()) {
            String narrative = String.format(
                    "当前用户消息长 %d 字符，命中复杂度特征词 %s，" +
                    "关键词密度评分 %.3f，上下文长度因子 %.3f，综合评分 %.3f (超过阈值 0.45)。" +
                    "判定为高复杂度任务，路由至 %s 以提升结构化输出稳定性。",
                    latestUserText.length(), reasoningResult.matchedKeywords,
                    reasoningScore, logLengthFactor, finalReasoningScore, reasoningModel);
            return new ModelRoutingService.Decision(reasoningModel, "SEMANTIC_VECTOR_HIGH_REASONING", 3,
                    narrative, metrics, reasoningResult.matchedKeywords, List.of());
        }

        if (lightweightScore > 0.40 && latestUserText.length() < 3000
                && reasoningScore < 0.30  // guard: do not downgrade if reasoning keywords are also present
                && fastModel != null && !fastModel.isBlank()) {
            String narrative = String.format(
                    "当前用户消息长 %d 字符，命中轻量操作特征词 %s，" +
                    "轻量密度评分 %.3f (超过阈值 0.40)，综合评分 %.3f 未达高阶门槛。" +
                    "判定为轻量任务，路由至 %s。",
                    latestUserText.length(), lightweightResult.matchedKeywords,
                    lightweightScore, finalReasoningScore, fastModel);
            return new ModelRoutingService.Decision(fastModel, "SEMANTIC_VECTOR_LOW_COMPLEXITY", 1,
                    narrative, metrics, lightweightResult.matchedKeywords, List.of());
        }

        if (balancedModel != null && !balancedModel.isBlank()) {
            List<String> combined = new ArrayList<>(reasoningResult.matchedKeywords);
            combined.addAll(lightweightResult.matchedKeywords);
            String narrative = String.format(
                    "当前用户消息长 %d 字符，综合评分 %.3f 与轻量分 %.3f 均在中等区间。" +
                    "判定为标准任务，路由至 %s。",
                    latestUserText.length(), finalReasoningScore, lightweightScore, balancedModel);
            return new ModelRoutingService.Decision(balancedModel, "SEMANTIC_VECTOR_BALANCED", 2,
                    narrative, metrics, combined, List.of());
        }

        String narrative = String.format("当前用户消息长 %d 字符，未达到显著复杂度偏向，保持默认模型配置。",
                latestUserText.length());
        return new ModelRoutingService.Decision(null, "SEMANTIC_VECTOR_KEEP_DEFAULT", 2,
                narrative, metrics, List.of(), List.of());
    }

    @Override
    public String strategyName() {
        return "semantic";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds RoutingTextInput from LlmRequest.
     * Kept here so SemanticVectorModelRouter works as a standalone strategy without
     * requiring injection of LatestUserMessageExtractor.
     */
    private RoutingTextInput buildRoutingInput(LlmRequest request) {
        if (request == null || request.contents() == null || request.contents().isEmpty()) {
            return RoutingTextInput.empty();
        }

        var contents = request.contents();
        String latestUserText = "";
        int totalChars = 0;

        for (int i = contents.size() - 1; i >= 0; i--) {
            var content = contents.get(i);
            String text = content.text() != null ? content.text() : "";
            if (latestUserText.isEmpty()
                    && content.role().map(r -> "user".equalsIgnoreCase(r.trim())).orElse(false)) {
                latestUserText = text;
            }
        }
        for (var content : contents) {
            totalChars += (content.text() != null ? content.text().length() : 0);
        }

        return new RoutingTextInput(latestUserText, totalChars);
    }

    private DensityResult calculateDensity(String lowerText, Map<String, Double> keywordWeights) {
        double accumulated = 0.0;
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Double> entry : keywordWeights.entrySet()) {
            if (lowerText.contains(entry.getKey())) {
                accumulated += entry.getValue();
                matched.add(entry.getKey());
            }
        }
        if (matched.isEmpty()) {
            return new DensityResult(0.0, matched);
        }
        return new DensityResult(1.0 - Math.exp(-accumulated), matched);
    }

    private record DensityResult(double score, List<String> matchedKeywords) {}
}
