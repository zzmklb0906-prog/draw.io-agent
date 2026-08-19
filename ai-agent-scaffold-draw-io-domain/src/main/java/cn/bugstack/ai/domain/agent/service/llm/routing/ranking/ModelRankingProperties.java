package cn.bugstack.ai.domain.agent.service.llm.routing.ranking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable weights for Multi-Objective Model Ranking Engine.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.model-routing.ranking")
public class ModelRankingProperties {

    private double capabilityWeight = 0.35;
    private double requirementWeight = 0.20;
    private double reliabilityWeight = 0.20;
    private double latencyWeight = 0.10;
    private double costWeight = 0.10;
    private double contextWeight = 0.05;

    public void validate() {
        if (capabilityWeight < 0 || requirementWeight < 0 || reliabilityWeight < 0
                || latencyWeight < 0 || costWeight < 0 || contextWeight < 0) {
            throw new IllegalArgumentException("Ranking weights must be non-negative");
        }
        double sum = totalWeight();
        if (sum <= 0.0001) {
            throw new IllegalArgumentException("Total ranking weight must be positive");
        }
    }

    public double totalWeight() {
        return capabilityWeight + requirementWeight + reliabilityWeight + latencyWeight + costWeight + contextWeight;
    }
}
