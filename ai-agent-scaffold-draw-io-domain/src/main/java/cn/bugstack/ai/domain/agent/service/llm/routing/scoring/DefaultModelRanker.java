package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelTier;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic Tier-First, Cheapest-Sufficient implementation of {@link ModelRanker}.
 *
 * <p><strong>Ranking &amp; Selection Precedence:</strong>
 * <ol>
 *   <li><strong>Policy Tier Gating:</strong> Target tier ({@code FAST}, {@code BALANCED}, {@code REASONING})
 *       derived deterministically from {@link TaskType}.</li>
 *   <li><strong>Cheapest-Sufficient Selection:</strong> Inspect the requested tier first, choose the cheapest
 *       sufficient hard-eligible model in that tier, and escalate to higher tiers only when no model in the
 *       current tier is sufficient.</li>
 *   <li><strong>Lexical Ordering &amp; Tie-breaking:</strong>
 *       Known pricing before unknown pricing, cost ascending, totalScore/capabilityFit descending,
 *       and case-insensitive model ID lexical ascending.</li>
 *   <li><strong>Fallback / Degradation:</strong> If no candidate in any permitted tier is sufficient,
 *       safely select the highest-capability candidate and record the degradation reason.</li>
 * </ol>
 * </p>
 */
@Component
public class DefaultModelRanker implements ModelRanker {

    private final ModelScorer modelScorer;

    public DefaultModelRanker(ModelScorer modelScorer) {
        this.modelScorer = modelScorer;
    }

    private static final Comparator<CandidateScore> COST_PREFERRED_COMPARATOR = (a, b) -> {
        boolean aHasPrice = a.estimatedCost() >= 0.0;
        boolean bHasPrice = b.estimatedCost() >= 0.0;
        int pCmp = Boolean.compare(bHasPrice, aHasPrice); // known pricing before unknown
        if (pCmp != 0) return pCmp;

        if (aHasPrice && bHasPrice) {
            int cCmp = Double.compare(a.estimatedCost(), b.estimatedCost()); // lower cost first
            if (cCmp != 0) return cCmp;
        }

        int sCmp = Double.compare(b.totalScore(), a.totalScore()); // higher total score first
        if (sCmp != 0) return sCmp;

        double aFit = a.breakdown() != null ? a.breakdown().capabilityFit() : 0.0;
        double bFit = b.breakdown() != null ? b.breakdown().capabilityFit() : 0.0;
        int fCmp = Double.compare(bFit, aFit); // higher capability-fit first
        if (fCmp != 0) return fCmp;

        String aId = a.model() != null && a.model().id() != null ? a.model().id() : "";
        String bId = b.model() != null && b.model().id() != null ? b.model().id() : "";
        return aId.compareToIgnoreCase(bId);
    };

    private static final Comparator<CandidateScore> CAPABILITY_FIRST_COMPARATOR = (a, b) -> {
        double aFit = a.breakdown() != null ? a.breakdown().capabilityFit() : 0.0;
        double bFit = b.breakdown() != null ? b.breakdown().capabilityFit() : 0.0;
        int fCmp = Double.compare(bFit, aFit); // highest capability-fit first
        if (fCmp != 0) return fCmp;

        int sCmp = Double.compare(b.totalScore(), a.totalScore()); // total score descending
        if (sCmp != 0) return sCmp;

        boolean aHasPrice = a.estimatedCost() >= 0.0;
        boolean bHasPrice = b.estimatedCost() >= 0.0;
        int pCmp = Boolean.compare(bHasPrice, aHasPrice); // known pricing before unknown
        if (pCmp != 0) return pCmp;

        if (aHasPrice && bHasPrice) {
            int cCmp = Double.compare(a.estimatedCost(), b.estimatedCost()); // lower cost first
            if (cCmp != 0) return cCmp;
        }

        String aId = a.model() != null && a.model().id() != null ? a.model().id() : "";
        String bId = b.model() != null && b.model().id() != null ? b.model().id() : "";
        return aId.compareToIgnoreCase(bId);
    };

    @Override
    public RankingResult rank(RoutingRequirement requirement, List<ModelProfile> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return RankingResult.empty();
        }

        // 1. Calculate cost bounds across the candidate batch
        double minCost = Double.MAX_VALUE;
        double maxCost = 0.0;
        boolean hasValidCost = false;

        for (ModelProfile candidate : candidates) {
            if (candidate == null) continue;
            double cost = modelScorer.estimateCost(requirement, candidate);
            if (cost >= 0) {
                hasValidCost = true;
                if (cost < minCost) minCost = cost;
                if (cost > maxCost) maxCost = cost;
            }
        }
        if (!hasValidCost) {
            minCost = 0.0;
            maxCost = 0.0;
        }

        // 2. Score all candidates (including non-compensating sufficiency check)
        List<CandidateScore> scores = new ArrayList<>();
        for (ModelProfile candidate : candidates) {
            if (candidate == null) continue;
            scores.add(modelScorer.score(requirement, candidate, minCost, maxCost));
        }

        if (scores.isEmpty()) {
            return RankingResult.empty();
        }

        // 3. Resolve Target Tier and ordered permitted escalation tiers
        TaskType taskType = requirement != null ? requirement.taskType() : TaskType.UNKNOWN;
        ModelTier targetTier = resolveTargetTier(taskType);
        List<ModelTier> permittedTiers = resolvePermittedTiers(targetTier);

        // 4. Select the first tier with sufficient candidates
        ModelTier selectedTier = null;
        for (ModelTier tier : permittedTiers) {
            if (scores.stream().anyMatch(cs -> getTier(cs) == tier && cs.sufficient())) {
                selectedTier = tier;
                break;
            }
        }

        CandidateScore selectedCandidate;
        String selectionReason;

        if (selectedTier != null) {
            // Within selected tier: known cost first, lower estimated cost, then deterministic quality/model-id tie breakers
            ModelTier tierToPick = selectedTier;
            selectedCandidate = scores.stream()
                    .filter(cs -> getTier(cs) == tierToPick && cs.sufficient())
                    .min(COST_PREFERRED_COMPARATOR)
                    .orElseThrow();
            selectionReason = resolveSufficientReason(targetTier, selectedTier);
        } else {
            // If no permitted tier is sufficient: highest capability-fit first, then total score and deterministic tie breakers
            if (targetTier == ModelTier.REASONING) {
                List<CandidateScore> reasoningCandidates = scores.stream()
                        .filter(cs -> getTier(cs) == ModelTier.REASONING)
                        .toList();
                if (!reasoningCandidates.isEmpty()) {
                    selectedCandidate = reasoningCandidates.stream()
                            .min(CAPABILITY_FIRST_COMPARATOR)
                            .orElseThrow();
                    selectionReason = "REASONING_BEST_EFFORT_HIGHEST_CAPABILITY";
                } else {
                    selectedCandidate = scores.stream()
                            .min(CAPABILITY_FIRST_COMPARATOR)
                            .orElseThrow();
                    selectionReason = "NO_HARD_ELIGIBLE_REASONING_MODEL_DEGRADED";
                }
            } else {
                List<CandidateScore> permittedCandidates = scores.stream()
                        .filter(cs -> permittedTiers.contains(getTier(cs)))
                        .toList();
                List<CandidateScore> pool = permittedCandidates.isEmpty() ? scores : permittedCandidates;
                selectedCandidate = pool.stream()
                        .min(CAPABILITY_FIRST_COMPARATOR)
                        .orElseThrow();
                selectionReason = "INSUFFICIENT_CAPABILITY_FALLBACK_HIGHEST_CAPABILITY";
            }
        }

        // 5. Keep every candidate in RankingResult for telemetry, but put the selected candidate first
        List<CandidateScore> remaining = new ArrayList<>(scores);
        remaining.remove(selectedCandidate);
        remaining.sort(buildRemainingComparator(selectedTier, permittedTiers));

        List<CandidateScore> ranked = new ArrayList<>(scores.size());
        ranked.add(selectedCandidate);
        ranked.addAll(remaining);

        return new RankingResult(ranked, selectionReason, targetTier);
    }

    public static ModelTier resolveTargetTier(TaskType taskType) {
        if (taskType == null) {
            return ModelTier.BALANCED;
        }
        return switch (taskType) {
            case SIMPLE_EDIT, FORMAT, SUMMARIZE, EXTRACT -> ModelTier.FAST;
            case DIAGNOSE, ANALYZE, PLAN, CODE_ANALYSIS, DRAWIO_ANALYSIS, DRAWIO_REVIEW -> ModelTier.REASONING;
            default -> ModelTier.BALANCED;
        };
    }

    private static List<ModelTier> resolvePermittedTiers(ModelTier targetTier) {
        if (targetTier == null) {
            return List.of(ModelTier.BALANCED, ModelTier.REASONING);
        }
        return switch (targetTier) {
            case FAST -> List.of(ModelTier.FAST, ModelTier.BALANCED, ModelTier.REASONING);
            case BALANCED -> List.of(ModelTier.BALANCED, ModelTier.REASONING);
            case REASONING -> List.of(ModelTier.REASONING);
        };
    }

    private static ModelTier getTier(CandidateScore cs) {
        return cs.model() != null && cs.model().tier() != null ? cs.model().tier() : ModelTier.BALANCED;
    }

    private static String resolveSufficientReason(ModelTier targetTier, ModelTier selectedTier) {
        if (targetTier == ModelTier.FAST) {
            return switch (selectedTier) {
                case FAST -> "FAST_CHEAPEST_SUFFICIENT";
                case BALANCED -> "TIER_ESCALATED_TO_BALANCED";
                case REASONING -> "TIER_ESCALATED_TO_REASONING";
            };
        } else if (targetTier == ModelTier.BALANCED) {
            return switch (selectedTier) {
                case BALANCED -> "BALANCED_CHEAPEST_SUFFICIENT";
                default -> "TIER_ESCALATED_TO_REASONING";
            };
        } else {
            return "REASONING_CHEAPEST_SUFFICIENT";
        }
    }

    private static Comparator<CandidateScore> buildRemainingComparator(ModelTier selectedTier, List<ModelTier> permittedTiers) {
        return (a, b) -> {
            boolean aSelectedTierSufficient = selectedTier != null && getTier(a) == selectedTier && a.sufficient();
            boolean bSelectedTierSufficient = selectedTier != null && getTier(b) == selectedTier && b.sufficient();
            if (aSelectedTierSufficient != bSelectedTierSufficient) {
                return aSelectedTierSufficient ? -1 : 1;
            }
            if (aSelectedTierSufficient) {
                return COST_PREFERRED_COMPARATOR.compare(a, b);
            }

            int aPermIdx = permittedTiers.indexOf(getTier(a));
            int bPermIdx = permittedTiers.indexOf(getTier(b));
            boolean aPermSufficient = aPermIdx >= 0 && a.sufficient();
            boolean bPermSufficient = bPermIdx >= 0 && b.sufficient();
            if (aPermSufficient != bPermSufficient) {
                return aPermSufficient ? -1 : 1;
            }
            if (aPermSufficient) {
                if (aPermIdx != bPermIdx) {
                    return Integer.compare(aPermIdx, bPermIdx);
                }
                return COST_PREFERRED_COMPARATOR.compare(a, b);
            }

            return CAPABILITY_FIRST_COMPARATOR.compare(a, b);
        };
    }
}
