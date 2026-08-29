package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCapabilities;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.catalog.SupportStatus;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic Weighted Model Scorer.
 *
 * <p><strong>Key Design Principles:</strong>
 * <ol>
 *   <li><strong>Requirement Satisfaction Fit:</strong> Evaluates capability satisfaction rather than raw model capability. Exceeding requirement yields a capped fit of 100 rather than unbounded bonus.</li>
 *   <li><strong>Dynamic Capability Weighting:</strong> Dimension weights are dynamically proportional to the requirement's demanded intensity.</li>
 *   <li><strong>Cost Preference:</strong> Normalized cost score favors cost-effective models when capabilities satisfy requirements.</li>
 *   <li><strong>Soft Adjustment:</strong> Soft adjustments (e.g. UNKNOWN feature penalty) do NOT perform hard constraint rejection.</li>
 * </ol>
 * </p>
 */
@Component
public class WeightedModelScorer implements ModelScorer {

    private final ModelScoringProperties properties;

    public WeightedModelScorer(ModelScoringProperties properties) {
        this.properties = properties != null ? properties : new ModelScoringProperties();
    }

    @Override
    public CandidateScore score(RoutingRequirement requirement,
                                ModelProfile model,
                                double minCostInBatch,
                                double maxCostInBatch) {
        if (model == null || model.capabilities() == null) {
            ScoreBreakdown emptyBreakdown = new ScoreBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of("INVALID_CAPABILITY_METADATA"));
            return new CandidateScore(model, 0.0, 0.0, emptyBreakdown, false);
        }

        List<String> evidence = new ArrayList<>();
        ModelCapabilities caps = model.capabilities();

        // 1. Calculate individual capability fits
        int reqReasoning = requirement != null ? requirement.reasoningRequired() : 0;
        int reqInstruction = requirement != null ? requirement.instructionFollowingRequired() : 0;
        int reqCoding = requirement != null ? requirement.codingRequired() : 0;
        int reqStructured = requirement != null ? requirement.structuredOutputRequired() : 0;
        int reqTool = requirement != null ? requirement.toolCallingRequired() : 0;

        double fitReasoning = calculateDimensionFit(reqReasoning, caps.reasoning());
        double fitInstruction = calculateDimensionFit(reqInstruction, caps.instructionFollowing());
        double fitCoding = calculateDimensionFit(reqCoding, caps.coding());
        double fitStructured = calculateDimensionFit(reqStructured, caps.structuredOutput());
        double fitTool = calculateDimensionFit(reqTool, caps.toolCalling());

        // Soft feature status adjustments on tool & structured output
        if (model.features() != null) {
            if (reqTool > 0) {
                fitTool = adjustFeatureFit(fitTool, model.features().toolCalling(), "toolCalling", evidence);
            }
            if (reqStructured > 0) {
                fitStructured = adjustFeatureFit(fitStructured, model.features().structuredOutput(), "structuredOutput", evidence);
            }
        }

        // 2. Dynamic capability weighting derived from requirement intensities
        double totalRequired = reqReasoning + reqInstruction + reqCoding + reqStructured + reqTool;
        double capabilityFit;
        if (totalRequired <= 0) {
            capabilityFit = 100.0;
            evidence.add("all soft requirements=0 -> capabilityFit=100");
        } else {
            capabilityFit = (reqReasoning * fitReasoning
                    + reqInstruction * fitInstruction
                    + reqCoding * fitCoding
                    + reqStructured * fitStructured
                    + reqTool * fitTool) / totalRequired;
        }

        // 3. Context & Output Headroom Scores
        long reqContext = requirement != null ? requirement.minContextWindowTokens() : 0L;
        long availContext = model.limits() != null ? model.limits().contextWindowTokens() : 0L;
        double contextHeadroomScore = calculateHeadroomScore(reqContext, availContext);

        long reqOutput = requirement != null ? requirement.expectedOutputTokens() : 0L;
        long availOutput = model.limits() != null ? model.limits().maxOutputTokens() : 0L;
        double outputHeadroomScore = calculateHeadroomScore(reqOutput, availOutput);

        // 4. Estimated Cost & Cost Score
        double estimatedCost = estimateCost(requirement, model);
        double costScore;
        if (estimatedCost < 0) {
            costScore = 50.0; // Pricing unavailable -> neutral score
            evidence.add("pricing unavailable -> costScore=50.0");
        } else if (maxCostInBatch == minCostInBatch) {
            costScore = 100.0;
        } else {
            costScore = 100.0 * (maxCostInBatch - estimatedCost) / (maxCostInBatch - minCostInBatch);
        }

        // 5. Uncertainty Penalty (e.g. UNKNOWN vision when vision required)
        double uncertaintyPenalty = 0.0;
        if (requirement != null && requirement.visionRequired() && model.features() != null) {
            if (model.features().vision() == SupportStatus.UNKNOWN) {
                uncertaintyPenalty = properties.getUnknownPenalty();
                evidence.add(String.format("vision=UNKNOWN with visionRequired -> penalty=-%.1f", uncertaintyPenalty));
            }
        }
        double certaintyScore = Math.max(0.0, 100.0 - uncertaintyPenalty);

        // 6. Weighted Sum & Clamp
        double wCap = properties.getCapabilityWeight();
        double wCost = properties.getCostWeight();
        double wContext = properties.getContextHeadroomWeight();
        double wOutput = properties.getOutputHeadroomWeight();
        double wCert = properties.getCertaintyWeight();

        double weightSum = wCap + wCost + wContext + wOutput + wCert;
        if (weightSum <= 0.0) {
            throw new IllegalStateException("Model scoring weight sum must be strictly positive: " + weightSum);
        }

        double totalScore = (capabilityFit * wCap
                + costScore * wCost
                + contextHeadroomScore * wContext
                + outputHeadroomScore * wOutput
                + certaintyScore * wCert) / weightSum;

        totalScore = Math.max(0.0, Math.min(100.0, totalScore));

        evidence.add(String.format("fit[reas=%.1f, inst=%.1f, code=%.1f, struct=%.1f, tool=%.1f] -> capFit=%.2f, cost=%.4f (score=%.1f), total=%.2f",
                fitReasoning, fitInstruction, fitCoding, fitStructured, fitTool, capabilityFit, estimatedCost, costScore, totalScore));

        // 7. Non-compensating Sufficiency Check
        boolean sufficient = checkSufficiency(requirement, model, evidence);

        ScoreBreakdown breakdown = new ScoreBreakdown(
                capabilityFit,
                fitReasoning,
                fitInstruction,
                fitCoding,
                fitStructured,
                fitTool,
                contextHeadroomScore,
                outputHeadroomScore,
                costScore,
                uncertaintyPenalty,
                evidence
        );

        return new CandidateScore(model, totalScore, estimatedCost, breakdown, sufficient);
    }

    public boolean checkSufficiency(RoutingRequirement requirement, ModelProfile model, List<String> evidence) {
        if (model == null || model.capabilities() == null) {
            return false;
        }
        if (requirement == null) {
            return true;
        }
        double threshold = properties.getSufficiencyThreshold();
        ModelCapabilities caps = model.capabilities();

        List<String> shortfalls = new ArrayList<>();
        if (requirement.reasoningRequired() > 0 && caps.reasoning() < requirement.reasoningRequired() * threshold) {
            shortfalls.add(String.format("reasoning[%d < %.1f]", caps.reasoning(), requirement.reasoningRequired() * threshold));
        }
        if (requirement.instructionFollowingRequired() > 0 && caps.instructionFollowing() < requirement.instructionFollowingRequired() * threshold) {
            shortfalls.add(String.format("instruction[%d < %.1f]", caps.instructionFollowing(), requirement.instructionFollowingRequired() * threshold));
        }
        if (requirement.codingRequired() > 0 && caps.coding() < requirement.codingRequired() * threshold) {
            shortfalls.add(String.format("coding[%d < %.1f]", caps.coding(), requirement.codingRequired() * threshold));
        }
        if (requirement.structuredOutputRequired() > 0) {
            if (caps.structuredOutput() < requirement.structuredOutputRequired() * threshold) {
                shortfalls.add(String.format("structured[%d < %.1f]", caps.structuredOutput(), requirement.structuredOutputRequired() * threshold));
            }
            if (model.features() != null && model.features().structuredOutput() == SupportStatus.UNSUPPORTED) {
                shortfalls.add("structured[UNSUPPORTED]");
            }
        }
        if (requirement.toolCallingRequired() > 0) {
            if (caps.toolCalling() < requirement.toolCallingRequired() * threshold) {
                shortfalls.add(String.format("toolCalling[%d < %.1f]", caps.toolCalling(), requirement.toolCallingRequired() * threshold));
            }
            if (model.features() != null && model.features().toolCalling() == SupportStatus.UNSUPPORTED) {
                shortfalls.add("toolCalling[UNSUPPORTED]");
            }
        }

        boolean isSufficient = shortfalls.isEmpty();
        if (evidence != null) {
            if (isSufficient) {
                evidence.add(String.format("sufficiency: SUFFICIENT (threshold=%.2f)", threshold));
            } else {
                evidence.add(String.format("sufficiency: INSUFFICIENT (%s)", String.join(", ", shortfalls)));
            }
        }
        return isSufficient;
    }

    @Override
    public double estimateCost(RoutingRequirement requirement, ModelProfile model) {
        if (model == null || model.pricing() == null || model.pricing().inputPerMillionTokens() == null || model.pricing().outputPerMillionTokens() == null) {
            return -1.0;
        }

        long inputTokens = 0L;
        if (requirement != null && requirement.evidence() != null) {
            inputTokens = requirement.evidence().estimatedContextTokens();
        }
        long outputTokens = requirement != null ? requirement.expectedOutputTokens() : 2048L;

        BigDecimal inPrice = model.pricing().inputPerMillionTokens();
        BigDecimal outPrice = model.pricing().outputPerMillionTokens();

        double costIn = (inputTokens / 1_000_000.0) * inPrice.doubleValue();
        double costOut = (outputTokens / 1_000_000.0) * outPrice.doubleValue();
        return costIn + costOut;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private double calculateDimensionFit(int required, int modelScore) {
        if (required <= 0) {
            return 100.0;
        }
        if (modelScore >= required) {
            return 100.0;
        }
        double ratio = (double) modelScore / required;
        // Non-linear power curve ensures noticeable penalty when capability significantly falls short of high demands
        return 100.0 * Math.pow(ratio, 2.0);
    }

    private double adjustFeatureFit(double baseFit, SupportStatus status, String featureName, List<String> evidence) {
        if (status == SupportStatus.UNSUPPORTED) {
            evidence.add(String.format("feature.%s=UNSUPPORTED -> fit discounted by 40%%", featureName));
            return baseFit * 0.60;
        } else if (status == SupportStatus.UNKNOWN) {
            evidence.add(String.format("feature.%s=UNKNOWN -> fit discounted by 10%%", featureName));
            return baseFit * 0.90;
        }
        return baseFit;
    }

    private double calculateHeadroomScore(long required, long available) {
        if (required <= 0L) {
            return 100.0;
        }
        if (available <= 0L) {
            return 0.0;
        }
        double ratio = (double) available / required;
        if (ratio >= 2.0) {
            return 100.0;
        }
        if (ratio <= 1.0) {
            return 60.0;
        }
        return Math.min(100.0, 60.0 + (ratio - 1.0) * 40.0);
    }
}
