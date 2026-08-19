package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintReason;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintViolation;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelFilterResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.RejectedModel;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.CandidateScore;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScorer;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RankingResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RoutingShadowComparison;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service for constructing and safely recording structured Shadow Routing Evaluation telemetry.
 */
@Slf4j
@Service
public class RoutingEvaluationService {

    private final List<RoutingEvaluationRecorder> recorders;
    private final ModelScorer modelScorer;

    public RoutingEvaluationService(List<RoutingEvaluationRecorder> recorders,
                                   ModelScorer modelScorer) {
        this.recorders = recorders != null ? recorders : List.of();
        this.modelScorer = modelScorer;
    }

    /**
     * Builds an immutable {@link RoutingEvaluationRecord} from the current invocation's lifecycle state.
     */
    public RoutingEvaluationRecord buildRecord(String invocationId,
                                               RoutingContext context,
                                               RoutingRequirement requirement,
                                               ModelFilterResult filterResult,
                                               RankingResult rankingResult,
                                               RoutingShadowComparison comparison) {
        String invId = invocationId != null ? invocationId : "unknown";
        String agentName = context != null ? context.agentName() : "unknown";
        TaskType taskType = requirement != null ? requirement.taskType() : TaskType.UNKNOWN;

        String actualModel = comparison != null ? comparison.actualModel() : null;
        String recommendedModel = comparison != null ? comparison.recommendedModel() : null;
        Boolean matched = comparison != null ? comparison.matched() : null;
        Double recommendedScore = comparison != null ? comparison.recommendedScore() : null;
        RoutingShadowComparison.SelectionSource actualSource = comparison != null ? comparison.actualSource() : RoutingShadowComparison.SelectionSource.LEGACY_ROUTER;

        int acceptedCount = filterResult != null ? filterResult.accepted().size() : 0;
        int rejectedCount = filterResult != null ? filterResult.rejected().size() : 0;

        Map<String, List<ConstraintReason>> rejectedReasons = new HashMap<>();
        if (filterResult != null && !filterResult.rejected().isEmpty()) {
            for (RejectedModel rm : filterResult.rejected()) {
                if (rm.model() != null) {
                    List<ConstraintReason> reasons = rm.violations().stream().map(ConstraintViolation::reason).toList();
                    rejectedReasons.put(rm.model().id(), reasons);
                }
            }
        }

        List<RankedCandidateSnapshot> topSnapshots = new ArrayList<>();
        Double top1Score = null;
        Double top2Score = null;
        Double scoreMargin = null;
        Double actualModelScore = null;
        Double estRecommendedCost = null;
        Double estActualCost = null;

        if (rankingResult != null && !rankingResult.isEmpty()) {
            List<CandidateScore> ranked = rankingResult.rankedCandidates();
            top1Score = ranked.get(0).totalScore();
            if (ranked.get(0).estimatedCost() >= 0.0) {
                estRecommendedCost = ranked.get(0).estimatedCost();
            }

            if (ranked.size() >= 2) {
                top2Score = ranked.get(1).totalScore();
                scoreMargin = top1Score - top2Score;
            }

            for (int i = 0; i < Math.min(3, ranked.size()); i++) {
                CandidateScore cs = ranked.get(i);
                topSnapshots.add(new RankedCandidateSnapshot(
                        cs.model().id(),
                        cs.totalScore(),
                        cs.breakdown().capabilityFit(),
                        cs.estimatedCost()
                ));
            }

            // Find actual model in ranked list
            if (actualModel != null) {
                for (CandidateScore cs : ranked) {
                    if (actualModel.equalsIgnoreCase(cs.model().id()) || actualModel.equalsIgnoreCase(cs.model().modelName())) {
                        actualModelScore = cs.totalScore();
                        if (cs.estimatedCost() >= 0.0) {
                            estActualCost = cs.estimatedCost();
                        }
                        break;
                    }
                }
            }
        }

        // If actual model was not in ranking but we have filter results, check if it was rejected
        Set<RoutingEvaluationFlag> flags = new HashSet<>();
        if (Boolean.TRUE.equals(matched)) {
            flags.add(RoutingEvaluationFlag.MATCHED);
        } else if (Boolean.FALSE.equals(matched)) {
            flags.add(RoutingEvaluationFlag.UNMATCHED);
        }

        if (recommendedModel == null) {
            flags.add(RoutingEvaluationFlag.NO_DYNAMIC_RECOMMENDATION);
        }
        if (acceptedCount == 0) {
            flags.add(RoutingEvaluationFlag.NO_ELIGIBLE_CANDIDATE);
        }
        if (scoreMargin != null && scoreMargin < 5.0) {
            flags.add(RoutingEvaluationFlag.LOW_SCORE_MARGIN);
        }
        if (requirement != null && requirement.visionRequired()) {
            flags.add(RoutingEvaluationFlag.VISION_REQUIRED);
        }
        if (filterResult != null && !filterResult.warnings().isEmpty()) {
            flags.add(RoutingEvaluationFlag.UNKNOWN_FEATURE_PRESENT);
        }

        // Check if actualModel was rejected by Hard Constraint Filter
        if (actualModel != null && filterResult != null) {
            boolean isHardRejected = filterResult.rejected().stream().anyMatch(rm ->
                    rm.model() != null && (actualModel.equalsIgnoreCase(rm.model().id()) || actualModel.equalsIgnoreCase(rm.model().modelName())));
            if (isHardRejected) {
                flags.add(RoutingEvaluationFlag.ACTUAL_MODEL_HARD_REJECTED);
            }
        }

        Double costDelta = null;
        if (estRecommendedCost != null && estActualCost != null) {
            costDelta = estRecommendedCost - estActualCost;
        } else if (estRecommendedCost == null || estActualCost == null) {
            flags.add(RoutingEvaluationFlag.PRICING_UNAVAILABLE);
        }

        RequirementSnapshot reqSnapshot = null;
        if (requirement != null) {
            reqSnapshot = new RequirementSnapshot(
                    requirement.reasoningRequired(),
                    requirement.instructionFollowingRequired(),
                    requirement.codingRequired(),
                    requirement.structuredOutputRequired(),
                    requirement.toolCallingRequired(),
                    requirement.visionRequired(),
                    requirement.minContextWindowTokens(),
                    requirement.expectedOutputTokens()
            );
        }

        return new RoutingEvaluationRecord(
                invId,
                agentName,
                taskType,
                actualModel,
                recommendedModel,
                matched,
                recommendedScore,
                actualSource,
                acceptedCount,
                rejectedCount,
                rejectedReasons,
                top1Score,
                top2Score,
                scoreMargin,
                actualModelScore,
                estRecommendedCost,
                estActualCost,
                costDelta,
                reqSnapshot,
                topSnapshots,
                flags,
                Instant.now()
        );
    }

    /**
     * Safely dispatches the record to all registered recorders with failure isolation.
     */
    public void tryRecord(RoutingEvaluationRecord record) {
        if (record == null) return;
        for (RoutingEvaluationRecorder recorder : recorders) {
            try {
                recorder.record(record);
            } catch (Exception e) {
                log.warn("RoutingEvaluationRecorder [{}] threw an exception (non-fatal): {}",
                        recorder.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
