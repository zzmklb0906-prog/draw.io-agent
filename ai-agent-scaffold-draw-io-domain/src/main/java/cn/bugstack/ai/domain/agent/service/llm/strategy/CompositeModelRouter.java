package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.RoutingTextInput;
import com.google.adk.models.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite Model Router — short-circuit responsibility chain (3 tiers).
 *
 * <p>Routes the LLM request through three fallback tiers in sequence.
 * Each tier may short-circuit if it produces a decisive L1 or L3 verdict.
 *
 * <p>Tier 1: Heuristic Semantic Router ({@link SemanticVectorModelRouter})
 * <p>Tier 2: Rule-Based Classifier ({@link LlmClassifierModelRouter})
 * <p>Tier 3: Rule-Based Fallback ({@link RuleBasedModelRouter})
 *
 * <p><strong>Phase 1 fix:</strong>
 * Uses {@link LatestUserMessageExtractor} to build a single {@link RoutingTextInput},
 * passing it across all tiers without repeated parsing.
 */
@Slf4j
@Component("compositeModelRouter")
public class CompositeModelRouter implements IModelRouterStrategy {

    private final LatestUserMessageExtractor extractor;
    private final SemanticVectorModelRouter semanticRouter;
    private final LlmClassifierModelRouter classifierRouter;
    private final RuleBasedModelRouter ruleRouter;

    public CompositeModelRouter(LatestUserMessageExtractor extractor,
                                SemanticVectorModelRouter semanticRouter,
                                LlmClassifierModelRouter classifierRouter,
                                RuleBasedModelRouter ruleRouter) {
        this.extractor = extractor;
        this.semanticRouter = semanticRouter;
        this.classifierRouter = classifierRouter;
        this.ruleRouter = ruleRouter;
    }

    @Override
    public ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel) {
        RoutingTextInput input = extractor.buildRoutingInput(request);
        List<Map<String, Object>> pipelineTrail = new ArrayList<>();

        // Tier 1: Heuristic semantic analysis (keyword-density based)
        ModelRoutingService.Decision semanticDecision = semanticRouter.routeFromInput(input, fastModel, balancedModel, reasoningModel);
        Map<String, Object> tier1 = new LinkedHashMap<>();
        tier1.put("tier", "Tier 1: 启发式语义分析 (Heuristic Semantic Router)");
        tier1.put("strategy", "heuristicSemanticModelRouter (keyword-density heuristic, NOT an embedding vector)");
        tier1.put("complexity", semanticDecision.complexity());
        tier1.put("score", semanticDecision.metrics().getOrDefault("finalReasoningScore", 0));
        tier1.put("matchedKeywords", semanticDecision.matchedKeywords());

        if (semanticDecision.complexity() == 3 || semanticDecision.complexity() == 1) {
            tier1.put("status", "HIT");
            tier1.put("detail", "启发式语义评分命中 L" + semanticDecision.complexity() + " 阈值，选定模型 " + semanticDecision.model());
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
            tier1.put("detail", "启发式语义评分未达 L1/L3 极值阈值，流转至下一级");
            pipelineTrail.add(tier1);
        }

        // Tier 2: Rule-based classifier (length and keyword intent heuristics)
        ModelRoutingService.Decision classifierDecision = classifierRouter.routeFromInput(input, fastModel, balancedModel, reasoningModel);
        Map<String, Object> tier2 = new LinkedHashMap<>();
        tier2.put("tier", "Tier 2: 规则分类器 (Rule-Based Classifier)");
        tier2.put("strategy", "ruleBasedClassifierModelRouter (rule-based intent heuristic, NOT an SLM)");
        tier2.put("complexity", classifierDecision.complexity());

        if (classifierDecision.complexity() == 3 || classifierDecision.complexity() == 1) {
            tier2.put("status", "HIT");
            tier2.put("detail", "启发式分类命中 L" + classifierDecision.complexity() + "，选定模型 " + classifierDecision.model());
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
            tier2.put("detail", "启发式分类未命中极值，流转至规则保底");
            pipelineTrail.add(tier2);
        }

        // Tier 3: Fallback Rule Router
        ModelRoutingService.Decision ruleDecision = ruleRouter.routeFromInput(input, fastModel, balancedModel, reasoningModel);
        Map<String, Object> tier3 = new LinkedHashMap<>();
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
