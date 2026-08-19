package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.LatencySensitivity;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelRuntimeProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.RuntimeHealth;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link ModelRankingEngine}.
 *
 * <p><strong>Multi-Objective Utility Scoring (Phase 5):</strong>
 * <ul>
 *   <li>Capability Fit (35%): Saturated fulfillment of required capabilities (avoids oversupply bias).</li>
 *   <li>Requirement Fit (20%): Task-type alignment.</li>
 *   <li>Reliability Fit (20%): Historical success & timeout rates; neutral (0.5) if unknown; excluded if UNAVAILABLE.</li>
 *   <li>Latency Fit (10%): Response latency fulfillment.</li>
 *   <li>Cost Fit (10%): Estimated request cost based on token expectations.</li>
 *   <li>Context Fit (5%): Context headroom capacity.</li>
 * </ul>
 * </p>
 */
@Component
public class DefaultModelRankingEngine implements ModelRankingEngine {

    private final ModelRankingProperties properties;

    public DefaultModelRankingEngine(ModelRankingProperties properties) {
        this.properties = properties != null ? properties : new ModelRankingProperties();
        this.properties.validate();
    }

    public DefaultModelRankingEngine() {
        this(new ModelRankingProperties());
    }

    @Override
    public List<RankedModel> rank(RoutingRequirement requirement,
                                  List<ModelProfile> candidates,
                                  Map<String, ModelRuntimeProfile> runtimeProfiles) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RankedModel> ranked = new ArrayList<>();
        for (ModelProfile candidate : candidates) {
            if (candidate == null || !candidate.enabled()) {
                continue;
            }

            ModelRuntimeProfile runtime = runtimeProfiles != null
                    ? runtimeProfiles.get(candidate.id().toLowerCase())
                    : null;

            // Health check: UNAVAILABLE models are filtered out from viable recommendations
            if (runtime != null && runtime.health() == RuntimeHealth.UNAVAILABLE) {
                continue;
            }

            boolean runtimeEvidenceAvailable = runtime != null && runtime.health() != RuntimeHealth.UNKNOWN && runtime.sampleCount() > 0;

            double capabilityFit = calculateCapabilityFit(requirement, candidate);
            double requirementFit = calculateRequirementFit(requirement, candidate);
            double reliabilityFit = calculateReliabilityFit(runtime);
            double latencyFit = calculateLatencyFit(requirement, runtime);
            Double estimatedCost = estimateCost(requirement, candidate);
            double costFit = calculateCostFit(estimatedCost);
            double contextFit = calculateContextFit(requirement, candidate);

            double totalWeight = properties.totalWeight();
            double weightedSum = (capabilityFit * properties.getCapabilityWeight())
                    + (requirementFit * properties.getRequirementWeight())
                    + (reliabilityFit * properties.getReliabilityWeight())
                    + (latencyFit * properties.getLatencyWeight())
                    + (costFit * properties.getCostWeight())
                    + (contextFit * properties.getContextWeight());

            double finalScore = Math.round((weightedSum / totalWeight) * 100.0) / 100.0;

            RankedModel.ScoreBreakdown breakdown = new RankedModel.ScoreBreakdown(
                    round2(capabilityFit),
                    round2(requirementFit),
                    round2(reliabilityFit),
                    round2(latencyFit),
                    round2(costFit),
                    round2(contextFit)
            );

            String reason = String.format(
                    "Model: %s | Final Score: %.2f | Reason: Capability Match: %.2f, Context Fit: %.2f, Requirement Match: %.2f, Reliability: %.2f, Latency: %.2f, Cost: %.2f",
                    candidate.id(), finalScore, breakdown.capabilityFit(), breakdown.contextFit(),
                    breakdown.requirementFit(), breakdown.reliabilityFit(), breakdown.latencyFit(), breakdown.costFit()
            );

            ranked.add(new RankedModel(candidate, finalScore, breakdown, reason, runtimeEvidenceAvailable, estimatedCost));
        }

        // Deterministic Multi-tier Tie Break:
        // 1. final score desc -> 2. reliability desc -> 3. cost asc (nulls last) -> 4. model id asc
        ranked.sort(Comparator.comparingDouble(RankedModel::score).reversed()
                .thenComparing((RankedModel r) -> r.breakdown().reliabilityFit(), Comparator.reverseOrder())
                .thenComparing((RankedModel r) -> r.estimatedCost() != null ? r.estimatedCost() : Double.MAX_VALUE)
                .thenComparing(r -> r.modelProfile().id()));

        return List.copyOf(ranked);
    }

    // -------------------------------------------------------------------------
    // Capability Fit with Saturation Mechanism (Anti-Oversupply)
    // -------------------------------------------------------------------------

    private double calculateCapabilityFit(RoutingRequirement requirement, ModelProfile model) {
        if (model.capabilities() == null) {
            return 0.5;
        }

        if (requirement == null) {
            return (model.capabilities().reasoning() + model.capabilities().instructionFollowing()) / 200.0;
        }

        List<Double> dimensionFits = new ArrayList<>();

        if (requirement.reasoningRequired() > 0) {
            int actual = model.capabilities().reasoning();
            int req = requirement.reasoningRequired();
            dimensionFits.add(actual >= req ? 1.0 : (double) actual / req);
        }
        if (requirement.codingRequired() > 0) {
            int actual = model.capabilities().coding();
            int req = requirement.codingRequired();
            dimensionFits.add(actual >= req ? 1.0 : (double) actual / req);
        }
        if (requirement.structuredOutputRequired() > 0) {
            int actual = model.capabilities().structuredOutput();
            int req = requirement.structuredOutputRequired();
            dimensionFits.add(actual >= req ? 1.0 : (double) actual / req);
        }
        if (requirement.needToolCalling()) {
            dimensionFits.add(model.supportsToolCalling() ? 1.0 : 0.0);
        }
        if (requirement.needVision()) {
            dimensionFits.add(model.supportsVision() ? 1.0 : 0.0);
        }

        if (dimensionFits.isEmpty()) {
            return Math.min(1.0, (model.capabilities().reasoning() + model.capabilities().instructionFollowing()) / 200.0);
        }

        double sum = 0.0;
        for (double fit : dimensionFits) {
            sum += fit;
        }
        return Math.min(1.0, sum / dimensionFits.size());
    }

    // -------------------------------------------------------------------------
    // Requirement & Task Fit
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Requirement & Task Fit (Fix 3: Anti-Double-Counting via TaskType alignment)
    // -------------------------------------------------------------------------

    private double calculateRequirementFit(RoutingRequirement requirement, ModelProfile model) {
        if (model.capabilities() == null) {
            return 0.7;
        }
        if (requirement == null || requirement.taskType() == null) {
            return 0.8;
        }

        cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCapabilities caps = model.capabilities();
        TaskType taskType = requirement.taskType();

        // Evaluate holistic profile affinity to task type emphasis (not repeating pure reasoning complexity)
        return switch (taskType) {
            case CODE_GENERATION -> (caps.coding() * 0.45 + caps.instructionFollowing() * 0.35 + caps.structuredOutput() * 0.20) / 100.0;
            case STRUCTURED_GENERATION, DRAWIO_REVIEW -> (caps.structuredOutput() * 0.50 + caps.instructionFollowing() * 0.50) / 100.0;
            case DRAWIO_GENERATION -> (caps.structuredOutput() * 0.40 + caps.instructionFollowing() * 0.30 + caps.reasoning() * 0.30) / 100.0;
            case TOOL_ORCHESTRATION -> ((model.supportsToolCalling() ? 100.0 : 0.0) * 0.50 + caps.instructionFollowing() * 0.50) / 100.0;
            case ANALYZE, DIAGNOSE -> (caps.reasoning() * 0.60 + caps.instructionFollowing() * 0.40) / 100.0;
            case SIMPLE_EDIT, FORMAT, GENERAL_CHAT -> (caps.instructionFollowing() * 0.70 + caps.reasoning() * 0.30) / 100.0;
            default -> (caps.instructionFollowing() * 0.50 + caps.reasoning() * 0.50) / 100.0;
        };
    }

    // -------------------------------------------------------------------------
    // Reliability Fit
    // -------------------------------------------------------------------------

    private double calculateReliabilityFit(ModelRuntimeProfile runtime) {
        if (runtime == null || runtime.health() == RuntimeHealth.UNKNOWN || runtime.sampleCount() <= 0) {
            return 0.5; // neutral score for new / unknown models
        }
        double base = runtime.successRate() * (1.0 - Math.min(1.0, runtime.timeoutRate()));
        if (runtime.health() == RuntimeHealth.DEGRADED) {
            base *= 0.7; // degraded penalty
        }
        return Math.max(0.0, Math.min(1.0, base));
    }

    // -------------------------------------------------------------------------
    // Latency Fit (Fix 1: Driven by RoutingRequirement.latencySensitivity)
    // -------------------------------------------------------------------------

    private double calculateLatencyFit(RoutingRequirement requirement, ModelRuntimeProfile runtime) {
        if (runtime == null || runtime.health() == RuntimeHealth.UNKNOWN || runtime.averageLatencyMs() <= 0) {
            return 0.5; // neutral score
        }
        double latencyMs = runtime.averageLatencyMs();
        LatencySensitivity sensitivity = requirement != null && requirement.latencySensitivity() != null
                ? requirement.latencySensitivity()
                : LatencySensitivity.NORMAL;

        // Baseline maximum acceptable response latency according to operational demand
        double baselineMaxMs = switch (sensitivity) {
            case HIGH -> 3000.0;   // aggressive drop for interactive edits / rapid chat
            case NORMAL -> 6000.0; // standard drop for general generation
            case LOW -> 12000.0;   // lenient tolerance for deep analysis / code generation
        };

        double fit = Math.max(0.05, 1.0 - Math.min(0.95, latencyMs / baselineMaxMs));
        return Math.max(0.0, Math.min(1.0, fit));
    }

    // -------------------------------------------------------------------------
    // Cost Estimation & Cost Fit (Fix 2: Proper token semantics)
    // -------------------------------------------------------------------------

    private Double estimateCost(RoutingRequirement requirement, ModelProfile model) {
        if (model.pricing() == null || model.pricing().inputPerMillionTokens() == null || model.pricing().outputPerMillionTokens() == null) {
            return null;
        }
        // Correct semantics: estimatedInputTokens (consumption), expectedOutputTokens (generation)
        // NOT minContextWindowTokens (which includes buffer & safety margins)
        long inputTokens = requirement != null ? requirement.estimatedInputTokens() : 1000L;
        long outputTokens = requirement != null ? requirement.expectedOutputTokens() : 1000L;

        BigDecimal inPrice = model.pricing().inputPerMillionTokens();
        BigDecimal outPrice = model.pricing().outputPerMillionTokens();

        double inCost = (inputTokens / 1_000_000.0) * inPrice.doubleValue();
        double outCost = (outputTokens / 1_000_000.0) * outPrice.doubleValue();
        return round4(inCost + outCost);
    }

    private double calculateCostFit(Double estimatedCost) {
        if (estimatedCost == null) {
            return 0.5; // neutral if pricing is missing (not hard constraint)
        }
        // Cheaper is better (normalized based on estimated cost scale)
        return Math.max(0.1, 1.0 - Math.min(0.9, estimatedCost * 100.0));
    }

    // -------------------------------------------------------------------------
    // Context Fit (Capacity Headroom)
    // -------------------------------------------------------------------------

    private double calculateContextFit(RoutingRequirement requirement, ModelProfile model) {
        if (requirement == null || requirement.minContextWindowTokens() <= 0) {
            return 1.0;
        }
        long required = requirement.minContextWindowTokens();
        long available = model.contextWindow();
        if (available >= required) {
            double ratio = (double) available / Math.max(1L, required * 2);
            return Math.min(1.0, 0.7 + (0.3 * Math.min(1.0, ratio)));
        } else {
            return 0.1;
        }
    }

    private static double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    private static double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }
}
