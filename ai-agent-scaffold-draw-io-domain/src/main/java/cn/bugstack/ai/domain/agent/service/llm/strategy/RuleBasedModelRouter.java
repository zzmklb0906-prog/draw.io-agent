package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import com.google.adk.models.LlmRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy 0: Heuristic Rule-based Model Router.
 */
@Component("ruleBasedModelRouter")
public class RuleBasedModelRouter implements IModelRouterStrategy {

    @Override
    public ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel) {
        String text = String.valueOf(request.contents());
        int length = text.length();
        String lower = text.toLowerCase();

        boolean complex = length > 12_000 || containsAny(lower, "架构", "跨模块", "根因", "安全审计", "重构", "checkpoint", "state machine", "调用链");
        boolean simple = length < 2_000 && containsAny(lower, "摘要", "改写", "标题", "格式", "校验", "翻译", "总结");

        List<String> matched = new ArrayList<>();
        for (String term : new String[]{"架构", "跨模块", "根因", "安全审计", "重构", "checkpoint", "state machine", "调用链", "摘要", "改写", "标题", "格式", "校验", "翻译", "总结"}) {
            if (lower.contains(term)) matched.add(term);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("textLength", length);
        metrics.put("matchedKeywords", matched);

        if (complex && reasoningModel != null && !reasoningModel.isBlank()) {
            String narrative = String.format("规则匹配：文本长 %d 字符或包含深度特征词 %s，命中复杂推理规则，路由至 %s。", length, matched, reasoningModel);
            return new ModelRoutingService.Decision(reasoningModel, "RULE_COMPLEX_REASONING", 3, narrative, metrics, matched, java.util.List.of());
        }
        if (simple && fastModel != null && !fastModel.isBlank()) {
            String narrative = String.format("规则匹配：文本长 %d 字符 (短文本) 且包含轻量词 %s，命中极速规则，路由至 %s。", length, matched, fastModel);
            return new ModelRoutingService.Decision(fastModel, "RULE_LOW_COMPLEXITY", 1, narrative, metrics, matched, java.util.List.of());
        }
        if (balancedModel != null && !balancedModel.isBlank()) {
            String narrative = String.format("规则保底：文本长 %d 字符，未触发极值规则，采用平衡档保底模型 %s。", length, balancedModel);
            return new ModelRoutingService.Decision(balancedModel, "RULE_BALANCED_DEFAULT", 2, narrative, metrics, matched, java.util.List.of());
        }
        return new ModelRoutingService.Decision(null, "RULE_KEEP_DEFAULT", 2, "保持默认配置", metrics, matched, java.util.List.of());
    }

    @Override
    public String strategyName() {
        return "rule";
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }
}
