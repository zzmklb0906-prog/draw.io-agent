package cn.bugstack.ai.domain.agent.service.llm.routing.decision;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.ModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.RankedModel;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Routing Decision Service.
 *
 * <p>Implements the separation of <strong>Ranking</strong> (what model is best in theory)
 * from <strong>Decision Policy</strong> (whether dynamic model takeover is currently permitted).</p>
 */
@Slf4j
@Service
public class RoutingDecisionService {

    private final ModelRankingService rankingService;
    private final RoutingRequirementService requirementService;
    private final RoutingPolicy routingPolicy;
    private final RoutingDecisionProperties properties;

    public RoutingDecisionService(ModelRankingService rankingService,
                                  RoutingRequirementService requirementService,
                                  RoutingPolicy routingPolicy,
                                  RoutingDecisionProperties properties) {
        this.rankingService = rankingService;
        this.requirementService = requirementService;
        this.routingPolicy = routingPolicy != null ? routingPolicy : new DefaultRoutingPolicy();
        this.properties = properties != null ? properties : new RoutingDecisionProperties();
    }

    /**
     * Decides the final model to invoke given context, legacy recommendation, and dynamic ranking.
     *
     * @param context routing context
     * @param legacySelectedModel model chosen by legacy composite router
     * @param legacyReason legacy decision reason
     * @return authoritative {@link RoutingDecision}
     */
    public RoutingDecision decide(RoutingContext context, String legacySelectedModel, String legacyReason) {
        // Priority 1: User Explicit Model Override
        if (properties.isAllowExplicitOverride() && context != null && StringUtils.isNotBlank(context.explicitModelName())) {
            String explicit = context.explicitModelName();
            log.info("Routing Decision: Explicit override requested: {}", explicit);
            return new RoutingDecision(
                    explicit,
                    DecisionSource.EXPLICIT_OVERRIDE,
                    List.of(),
                    1.0,
                    "Explicit caller model override",
                    null,
                    legacySelectedModel,
                    false
            );
        }

        // Priority 2: Analyze requirement and run dynamic ranking
        RoutingRequirement requirement = resolveRequirement(context);
        List<RankedModel> rankedModels = rankingService != null ? rankingService.rank(requirement) : List.of();

        String dynamicTop1 = null;
        double confidence = 0.0;
        List<String> backupCandidates = new ArrayList<>();

        if (!rankedModels.isEmpty()) {
            RankedModel top1 = rankedModels.get(0);
            dynamicTop1 = top1.modelProfile().modelName();

            if (rankedModels.size() > 1) {
                confidence = Math.round((top1.score() - rankedModels.get(1).score()) * 100.0) / 100.0;
                for (int i = 1; i < rankedModels.size(); i++) {
                    backupCandidates.add(rankedModels.get(i).modelProfile().modelName());
                }
            } else {
                confidence = top1.score();
            }
        }

        // Priority 3: Evaluate Routing Policy for Takeover
        boolean allowed = routingPolicy.allowDynamicRouting(context, requirement, rankedModels, legacySelectedModel);

        RoutingMode mode = properties.getMode() != null ? properties.getMode() : RoutingMode.SHADOW;
        String finalModel;
        DecisionSource source;
        String decisionReason;
        boolean takenOver;

        if (allowed && StringUtils.isNotBlank(dynamicTop1)) {
            finalModel = dynamicTop1;
            takenOver = true;
            source = mode == RoutingMode.CANARY ? DecisionSource.DYNAMIC_CANARY : DecisionSource.DYNAMIC_FORCED;
            decisionReason = String.format("Dynamic takeover granted under mode [%s], top1=%s, confidence=%.2f", mode, dynamicTop1, confidence);
        } else {
            finalModel = legacySelectedModel;
            takenOver = false;
            source = mode == RoutingMode.LEGACY ? DecisionSource.LEGACY : DecisionSource.DYNAMIC_SHADOW;
            decisionReason = String.format("Legacy router selected [%s] (mode=%s, dynamicTop1=%s, confidence=%.2f)",
                    legacySelectedModel, mode, dynamicTop1, confidence);
        }

        // Trace & observation audit log
        RoutingDecisionTrace trace = new RoutingDecisionTrace(
                context != null ? context.requestId() : "req-unknown",
                mode,
                legacySelectedModel,
                dynamicTop1,
                finalModel,
                source,
                confidence,
                decisionReason
        );
        log.info("Routing Decision Trace: {}", trace);

        return new RoutingDecision(
                finalModel,
                source,
                backupCandidates,
                confidence,
                decisionReason,
                dynamicTop1,
                legacySelectedModel,
                takenOver
        );
    }

    private RoutingRequirement resolveRequirement(RoutingContext context) {
        if (requirementService != null && context != null) {
            return requirementService.analyze(context);
        }
        return RoutingRequirement.defaultRequirement(context != null ? context.agentName() : "unknown");
    }
}
