package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

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
}
