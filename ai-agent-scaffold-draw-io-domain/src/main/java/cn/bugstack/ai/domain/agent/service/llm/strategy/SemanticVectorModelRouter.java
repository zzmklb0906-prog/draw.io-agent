package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import com.google.adk.models.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Strategy 1: Semantic Vector & Multi-Dimensional Feature Space Router.
 * Computes semantic feature distances and normalized domain vectors without external API overhead.
 */
@Slf4j
@Component("semanticVectorModelRouter")
public class SemanticVectorModelRouter implements IModelRouterStrategy {

    // Feature centroid weights for high complexity tasks
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

    // Feature centroid weights for lightweight tasks
    private static final Map<String, Double> LIGHTWEIGHT_KEYWORDS = new HashMap<>() {{
        put("摘要", 0.9); put("summary", 0.9);
        put("改写", 0.85); put("rewrite", 0.85);
        put("格式化", 0.88); put("format", 0.88);
        put("校验", 0.8); put("validate", 0.8);
        put("翻译", 0.85); put("translate", 0.85);
        put("提取", 0.8); put("extract", 0.8);
        put("纠错", 0.8); put("title", 0.8);
    }};

    @Override
    public ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel) {
        String text = String.valueOf(request.contents());
        String lowerText = text.toLowerCase();

        // 1. Calculate high-complexity semantic vector score & collect matched keywords
        DensityResult reasoningResult = calculateSemanticDensityWithKeywords(lowerText, REASONING_KEYWORDS);
        double reasoningScore = reasoningResult.score;

        // 2. Calculate lightweight semantic vector score & collect matched keywords
        DensityResult lightweightResult = calculateSemanticDensityWithKeywords(lowerText, LIGHTWEIGHT_KEYWORDS);
        double lightweightScore = lightweightResult.score;

        // 3. Normalize with text length scaling
        double logLengthFactor = Math.min(1.0, Math.log10(Math.max(1, text.length())) / 4.5); // normalized length factor
        double finalReasoningScore = (reasoningScore * 0.7) + (logLengthFactor * 0.3);

        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("textLength", text.length());
        metrics.put("reasoningScore", Math.round(reasoningScore * 1000.0) / 1000.0);
        metrics.put("lightweightScore", Math.round(lightweightScore * 1000.0) / 1000.0);
        metrics.put("logLengthFactor", Math.round(logLengthFactor * 1000.0) / 1000.0);
        metrics.put("finalReasoningScore", Math.round(finalReasoningScore * 1000.0) / 1000.0);
        metrics.put("reasoningThreshold", 0.45);
        metrics.put("lightweightThreshold", 0.40);

        log.debug("Semantic Vector Router score: reasoning={}/final={}, lightweight={}, matchedReasoning={}, matchedLightweight={}", 
                reasoningScore, finalReasoningScore, lightweightScore, reasoningResult.matchedKeywords, lightweightResult.matchedKeywords);

        if (finalReasoningScore > 0.45 && reasoningModel != null && !reasoningModel.isBlank()) {
            String narrative = String.format("请求文本总长 %d 字符，命中高难度特征词 %s，高阶语义密度评分 %.3f，长度因子 %.3f，综合推理分 %.3f (超过阈值 0.45)。判定为 L3 级深度推理任务，自动升阶至 %s 以确保复杂多分支逻辑与代码/XML 语法的 100%% 正确性。",
                    text.length(), reasoningResult.matchedKeywords, reasoningScore, logLengthFactor, finalReasoningScore, reasoningModel);
            return new ModelRoutingService.Decision(reasoningModel, "SEMANTIC_VECTOR_HIGH_REASONING", 3, narrative, metrics, reasoningResult.matchedKeywords, java.util.List.of());
        }

        if (lightweightScore > 0.40 && text.length() < 3000 && fastModel != null && !fastModel.isBlank()) {
            String narrative = String.format("请求文本总长 %d 字符 (小于 3000)，命中轻量操作特征词 %s，轻量语义密度评分 %.3f (超过阈值 0.40)，综合推理分 %.3f 未达高阶门槛。判定为 L1 级轻量任务，自动路由至 %s 极速响应并降低首包延迟。",
                    text.length(), lightweightResult.matchedKeywords, lightweightScore, finalReasoningScore, fastModel);
            return new ModelRoutingService.Decision(fastModel, "SEMANTIC_VECTOR_LOW_COMPLEXITY", 1, narrative, metrics, lightweightResult.matchedKeywords, java.util.List.of());
        }

        if (balancedModel != null && !balancedModel.isBlank()) {
            java.util.List<String> combined = new java.util.ArrayList<>(reasoningResult.matchedKeywords);
            combined.addAll(lightweightResult.matchedKeywords);
            String narrative = String.format("请求文本总长 %d 字符，综合推理分 %.3f 与轻量分 %.3f 均位于中等区间。判定为 L2 级标准任务，路由至 %s 兼顾理解准确率与推理成本。",
                    text.length(), finalReasoningScore, lightweightScore, balancedModel);
            return new ModelRoutingService.Decision(balancedModel, "SEMANTIC_VECTOR_BALANCED", 2, narrative, metrics, combined, java.util.List.of());
        }

        String narrative = String.format("请求文本总长 %d 字符，未达到显著复杂度偏向，保持默认模型配置。", text.length());
        return new ModelRoutingService.Decision(null, "SEMANTIC_VECTOR_KEEP_DEFAULT", 2, narrative, metrics, java.util.List.of(), java.util.List.of());
    }

    @Override
    public String strategyName() {
        return "semantic";
    }

    private DensityResult calculateSemanticDensityWithKeywords(String text, Map<String, Double> keywordWeights) {
        double accumulatedWeight = 0.0;
        java.util.List<String> matched = new java.util.ArrayList<>();
        for (Map.Entry<String, Double> entry : keywordWeights.entrySet()) {
            if (text.contains(entry.getKey())) {
                accumulatedWeight += entry.getValue();
                matched.add(entry.getKey());
            }
        }
        if (matched.isEmpty()) return new DensityResult(0.0, matched);
        double score = 1.0 - Math.exp(-accumulatedWeight);
        return new DensityResult(score, matched);
    }

    private record DensityResult(double score, java.util.List<String> matchedKeywords) {}
}
