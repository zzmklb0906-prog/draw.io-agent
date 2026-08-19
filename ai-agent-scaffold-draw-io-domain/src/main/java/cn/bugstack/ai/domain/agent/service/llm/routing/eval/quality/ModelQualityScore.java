package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import java.util.List;
import java.util.Map;

/**
 * Multi-dimensional quality evaluation score for a model's benchmark output.
 */
public record ModelQualityScore(
        double totalScore,
        Map<String, Double> dimensions,
        boolean passed,
        List<String> issues
) {
    public ModelQualityScore {
        dimensions = dimensions != null ? Map.copyOf(dimensions) : Map.of();
        issues = issues != null ? List.copyOf(issues) : List.of();
    }

    public static ModelQualityScore of(double totalScore, Map<String, Double> dimensions, boolean passed, List<String> issues) {
        return new ModelQualityScore(Math.max(0.0, Math.min(100.0, totalScore)), dimensions, passed, issues);
    }

    public static ModelQualityScore failed(String reason) {
        return new ModelQualityScore(0.0, Map.of(), false, List.of(reason));
    }
}
