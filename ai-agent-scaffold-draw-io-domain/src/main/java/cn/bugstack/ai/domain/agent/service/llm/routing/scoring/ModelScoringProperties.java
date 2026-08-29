package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Model Scoring.
 *
 * <p><strong>Calibration Notice:</strong>
 * Weights and penalties configured here represent initial project routing calibration values,
 * tuned for balancing capability fit, capacity headroom, and cost efficiency.
 * They are NOT official vendor benchmarks.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.model-scoring")
public class ModelScoringProperties {

    /** Weight for soft capability requirement satisfaction (default 0.80). */
    private double capabilityWeight = 0.80;

    /** Weight for cost preference (default 0.05). */
    private double costWeight = 0.05;

    /** Weight for context capacity headroom (default 0.05). */
    private double contextHeadroomWeight = 0.05;

    /** Weight for max output capacity headroom (default 0.05). */
    private double outputHeadroomWeight = 0.05;

    /** Weight for certainty / support assurance (default 0.05). */
    private double certaintyWeight = 0.05;

    /** Penalty points deducted for UNKNOWN vision feature when vision is required (default 10.0). */
    private double unknownPenalty = 10.0;

    /** Minimum capability-to-requirement ratio required for a model to be considered sufficient across all demanded dimensions (default 0.85). */
    private double sufficiencyThreshold = 0.85;

    @PostConstruct
    public void validate() {
        if (capabilityWeight < 0.0) {
            throw new IllegalArgumentException("capabilityWeight must not be negative: " + capabilityWeight);
        }
        if (costWeight < 0.0) {
            throw new IllegalArgumentException("costWeight must not be negative: " + costWeight);
        }
        if (contextHeadroomWeight < 0.0) {
            throw new IllegalArgumentException("contextHeadroomWeight must not be negative: " + contextHeadroomWeight);
        }
        if (outputHeadroomWeight < 0.0) {
            throw new IllegalArgumentException("outputHeadroomWeight must not be negative: " + outputHeadroomWeight);
        }
        if (certaintyWeight < 0.0) {
            throw new IllegalArgumentException("certaintyWeight must not be negative: " + certaintyWeight);
        }
        if (unknownPenalty < 0.0) {
            throw new IllegalArgumentException("unknownPenalty must not be negative: " + unknownPenalty);
        }
        if (sufficiencyThreshold <= 0.0 || sufficiencyThreshold > 1.0) {
            throw new IllegalArgumentException("sufficiencyThreshold must be between 0 (exclusive) and 1 (inclusive): " + sufficiencyThreshold);
        }

        double totalWeight = capabilityWeight + costWeight + contextHeadroomWeight + outputHeadroomWeight + certaintyWeight;
        if (totalWeight <= 0.0) {
            throw new IllegalStateException("Total scoring weight sum must be strictly positive: " + totalWeight);
        }
    }
}
