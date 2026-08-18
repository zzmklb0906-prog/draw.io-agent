package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.RoutingTextInput;
import com.google.adk.models.LlmRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Heuristic fallback rule-based router (Tier 3 / standalone).
 *
 * <p><strong>Phase 1 fix:</strong> Uses {@link LatestUserMessageExtractor} so that keyword
 * and length checks operate on {@code latestUserText} only, not the full conversation history.
 *
 * <p>Known limitations (to be addressed in Phase 2+):
 * <ul>
 *   <li>No negation detection — "不需要架构分析" still matches "架构".</li>
 * </ul>
 */
@Component("ruleBasedModelRouter")
public class RuleBasedModelRouter implements IModelRouterStrategy {

    private final LatestUserMessageExtractor extractor;

    public RuleBasedModelRouter(LatestUserMessageExtractor extractor) {
        this.extractor = extractor;
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
        int latestLen = latestUserText.length();
        String lower = latestUserText.toLowerCase();

        boolean complex = containsAny(lower, "架构", "跨模块", "根因", "安全审计", "重构",
                "checkpoint", "state machine", "调用链", "分布式", "状态机", "并发", "一致性");
        boolean simple = latestLen < 2_000
                && containsAny(lower, "摘要", "改写", "标题", "格式", "校验", "翻译", "总结");
        // Additional guard: do NOT classify as simple if complex keywords are also present
        boolean simpleOnly = simple && !complex;

        List<String> matched = new ArrayList<>();
        for (String term : new String[]{"架构", "跨模块", "根因", "安全审计", "重构",
                "checkpoint", "state machine", "调用链", "分布式", "状态机", "并发", "一致性",
                "摘要", "改写", "标题", "格式", "校验", "翻译", "总结"}) {
            if (lower.contains(term)) matched.add(term);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("latestUserTextLength", latestLen);
        metrics.put("totalContextChars", input.totalContextChars());
        metrics.put("matchedKeywords", matched);

        if (complex && reasoningModel != null && !reasoningModel.isBlank()) {
            String narrative = String.format(
                    "规则匹配：当前用户消息长 %d 字符，包含复杂度特征词 %s，路由至 %s。",
                    latestLen, matched, reasoningModel);
            return new ModelRoutingService.Decision(reasoningModel, "RULE_COMPLEX_REASONING", 3,
                    narrative, metrics, matched, List.of());
        }
        if (simpleOnly && fastModel != null && !fastModel.isBlank()) {
            String narrative = String.format(
                    "规则匹配：当前用户消息长 %d 字符（短文本）且包含轻量词 %s，路由至 %s。",
                    latestLen, matched, fastModel);
            return new ModelRoutingService.Decision(fastModel, "RULE_LOW_COMPLEXITY", 1,
                    narrative, metrics, matched, List.of());
        }
        if (balancedModel != null && !balancedModel.isBlank()) {
            String narrative = String.format(
                    "规则保底：当前用户消息长 %d 字符，未触发极值规则，采用平衡档保底模型 %s。",
                    latestLen, balancedModel);
            return new ModelRoutingService.Decision(balancedModel, "RULE_BALANCED_DEFAULT", 2,
                    narrative, metrics, matched, List.of());
        }
        return new ModelRoutingService.Decision(null, "RULE_KEEP_DEFAULT", 2,
                "保持默认配置", metrics, matched, List.of());
    }

    @Override
    public String strategyName() {
        return "rule";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }
}
