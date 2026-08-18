package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import com.google.adk.models.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite Intelligent Router: Combines Semantic Vector, SLM Classifier & Rule fallback in a multi-tier pipeline.
 */
@Slf4j
@Component("compositeModelRouter")
public class CompositeModelRouter implements IModelRouterStrategy {

    private final SemanticVectorModelRouter semanticRouter;
    private final LlmClassifierModelRouter classifierRouter;
    private final RuleBasedModelRouter ruleRouter;

    public CompositeModelRouter(SemanticVectorModelRouter semanticRouter,
                                LlmClassifierModelRouter classifierRouter,
                                RuleBasedModelRouter ruleRouter) {
        this.semanticRouter = semanticRouter;
        this.classifierRouter = classifierRouter;
        this.ruleRouter = ruleRouter;
    }

    @Override
    public ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel) {
        java.util.List<Map<String, Object>> pipelineTrail = new java.util.ArrayList<>();

        // Tier 1: Semantic Vector Check
        ModelRoutingService.Decision semanticDecision = semanticRouter.route(request, fastModel, balancedModel, reasoningModel);
        Map<String, Object> tier1 = new java.util.LinkedHashMap<>();
        tier1.put("tier", "Tier 1: 语义向量特征计算");
        tier1.put("strategy", "semanticVectorModelRouter");
        tier1.put("complexity", semanticDecision.complexity());
        tier1.put("score", semanticDecision.metrics().getOrDefault("finalReasoningScore", 0));
        tier1.put("matchedKeywords", semanticDecision.matchedKeywords());

        if (semanticDecision.complexity() == 3 || semanticDecision.complexity() == 1) {
            tier1.put("status", "HIT");
            tier1.put("detail", "明确决断复杂度 L" + semanticDecision.complexity() + "，命中模型 " + semanticDecision.model());
            pipelineTrail.add(tier1);
            return new ModelRoutingService.Decision(
                    semanticDecision.model(),
                    "COMPOSITE_TIER1_" + semanticDecision.reason(),
                    semanticDecision.complexity(),
                    semanticDecision.narrative(),
                    semanticDecision.metrics(),
                    semanticDecision.matchedKeywords(),
                    pipelineTrail
            );
        } else {
            tier1.put("status", "PASSED");
            tier1.put("detail", "语义特征未达到 L1/L3 极值阈值，流转至下一级分类器");
            pipelineTrail.add(tier1);
        }

        // Tier 2: SLM Classifier Check
        ModelRoutingService.Decision classifierDecision = classifierRouter.route(request, fastModel, balancedModel, reasoningModel);
        Map<String, Object> tier2 = new java.util.LinkedHashMap<>();
        tier2.put("tier", "Tier 2: 意图结构模式分类");
        tier2.put("strategy", "llmClassifierModelRouter");
        tier2.put("complexity", classifierDecision.complexity());

        if (classifierDecision.complexity() == 3 || classifierDecision.complexity() == 1) {
            tier2.put("status", "HIT");
            tier2.put("detail", "明确决断复杂度 L" + classifierDecision.complexity() + "，命中模型 " + classifierDecision.model());
            pipelineTrail.add(tier2);
            return new ModelRoutingService.Decision(
                    classifierDecision.model(),
                    "COMPOSITE_TIER2_" + classifierDecision.reason(),
                    classifierDecision.complexity(),
                    classifierDecision.narrative(),
                    classifierDecision.metrics(),
                    classifierDecision.matchedKeywords(),
                    pipelineTrail
            );
        } else {
            tier2.put("status", "PASSED");
            tier2.put("detail", "任务属于常规标准交互，流转至规则保底");
            pipelineTrail.add(tier2);
        }

        // Tier 3: Fallback Rule Router
        ModelRoutingService.Decision ruleDecision = ruleRouter.route(request, fastModel, balancedModel, reasoningModel);
        Map<String, Object> tier3 = new java.util.LinkedHashMap<>();
        tier3.put("tier", "Tier 3: 默认规则与平衡保底");
        tier3.put("strategy", "ruleBasedModelRouter");
        tier3.put("status", "HIT");
        tier3.put("complexity", ruleDecision.complexity());
        tier3.put("detail", "选用平衡档保底模型 " + ruleDecision.model());
        pipelineTrail.add(tier3);

        return new ModelRoutingService.Decision(
                ruleDecision.model(),
                "COMPOSITE_FALLBACK_" + ruleDecision.reason(),
                ruleDecision.complexity(),
                semanticDecision.narrative().isBlank() ? ruleDecision.narrative() : semanticDecision.narrative(),
                semanticDecision.metrics().isEmpty() ? ruleDecision.metrics() : semanticDecision.metrics(),
                semanticDecision.matchedKeywords(),
                pipelineTrail
        );
    }

    @Override
    public String strategyName() {
        return "composite";
    }
}
