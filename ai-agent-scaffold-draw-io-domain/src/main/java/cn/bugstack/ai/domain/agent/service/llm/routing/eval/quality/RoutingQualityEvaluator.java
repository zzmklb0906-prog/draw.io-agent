package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for computing routing quality, quality regret, and router efficiency against benchmark outputs.
 */
@Service
public class RoutingQualityEvaluator {

    private final BenchmarkExecutionProperties properties;

    public RoutingQualityEvaluator() {
        this(new BenchmarkExecutionProperties());
    }

    public RoutingQualityEvaluator(BenchmarkExecutionProperties properties) {
        this.properties = properties != null ? properties : new BenchmarkExecutionProperties();
    }

    public RoutingQualityEvaluation evaluate(
            BenchmarkCase benchmarkCase,
            List<BenchmarkModelResult> modelResults,
            String dynamicRecommendedModel,
            String legacyModel) {

        if (modelResults == null || modelResults.isEmpty()) {
            return new RoutingQualityEvaluation(
                    benchmarkCase.caseId(),
                    dynamicRecommendedModel,
                    legacyModel,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    RoutingQualityClassification.NO_VALID_COMPARISON,
                    RoutingQualityClassification.NO_VALID_COMPARISON
            );
        }

        // Filter successful model executions with quality scores
        List<BenchmarkModelResult> successfulResults = modelResults.stream()
                .filter(r -> r != null && r.success() && r.qualityScore() != null)
                .toList();

        if (successfulResults.isEmpty()) {
            return new RoutingQualityEvaluation(
                    benchmarkCase.caseId(),
                    dynamicRecommendedModel,
                    legacyModel,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    RoutingQualityClassification.NO_VALID_COMPARISON,
                    RoutingQualityClassification.NO_VALID_COMPARISON
            );
        }

        // Find max quality score
        double bestQualityScore = successfulResults.stream()
                .mapToDouble(r -> r.qualityScore().totalScore())
                .max()
                .orElse(0.0);

        // Find best quality model (if tied, prefer cheaper / lexical order)
        double tolerance = properties.getQualityTieTolerance();
        List<BenchmarkModelResult> bestCandidates = successfulResults.stream()
                .filter(r -> Math.abs(r.qualityScore().totalScore() - bestQualityScore) < 1e-6)
                .sorted((a, b) -> {
                    double costA = a.estimatedCost() != null ? a.estimatedCost() : Double.MAX_VALUE;
                    double costB = b.estimatedCost() != null ? b.estimatedCost() : Double.MAX_VALUE;
                    int cmp = Double.compare(costA, costB);
                    if (cmp != 0) return cmp;
                    return a.modelName().compareTo(b.modelName());
                })
                .toList();
        String bestQualityModel = !bestCandidates.isEmpty() ? bestCandidates.get(0).modelName() : null;

        // Find cost-efficient best model (within quality tolerance, lowest cost)
        List<BenchmarkModelResult> equivalentCandidates = successfulResults.stream()
                .filter(r -> (bestQualityScore - r.qualityScore().totalScore()) <= tolerance)
                .sorted((a, b) -> {
                    double costA = a.estimatedCost() != null ? a.estimatedCost() : Double.MAX_VALUE;
                    double costB = b.estimatedCost() != null ? b.estimatedCost() : Double.MAX_VALUE;
                    int cmp = Double.compare(costA, costB);
                    if (cmp != 0) return cmp;
                    // If cost tied, higher score first
                    int scoreCmp = Double.compare(b.qualityScore().totalScore(), a.qualityScore().totalScore());
                    if (scoreCmp != 0) return scoreCmp;
                    return a.modelName().compareTo(b.modelName());
                })
                .toList();
        String costEfficientBestModel = !equivalentCandidates.isEmpty() ? equivalentCandidates.get(0).modelName() : bestQualityModel;
        BenchmarkModelResult costEfficientResult = !equivalentCandidates.isEmpty() ? equivalentCandidates.get(0) : null;

        // Find score and cost for dynamic and legacy models
        BenchmarkModelResult dynamicResult = findResult(successfulResults, dynamicRecommendedModel);
        BenchmarkModelResult legacyResult = findResult(successfulResults, legacyModel);

        Double dynamicScore = dynamicResult != null ? dynamicResult.qualityScore().totalScore() : null;
        Double legacyScore = legacyResult != null ? legacyResult.qualityScore().totalScore() : null;

        Double dynamicRegret = dynamicScore != null ? Math.max(0.0, bestQualityScore - dynamicScore) : null;
        Double legacyRegret = legacyScore != null ? Math.max(0.0, bestQualityScore - legacyScore) : null;

        RoutingQualityClassification dynamicClass = classify(dynamicResult, bestQualityScore, tolerance, costEfficientResult);
        RoutingQualityClassification legacyClass = classify(legacyResult, bestQualityScore, tolerance, costEfficientResult);

        return new RoutingQualityEvaluation(
                benchmarkCase.caseId(),
                dynamicRecommendedModel,
                legacyModel,
                bestQualityModel,
                costEfficientBestModel,
                dynamicScore,
                legacyScore,
                bestQualityScore,
                dynamicRegret,
                legacyRegret,
                dynamicClass,
                legacyClass
        );
    }

    private BenchmarkModelResult findResult(List<BenchmarkModelResult> results, String modelName) {
        if (StringUtils.isBlank(modelName)) return null;
        return results.stream()
                .filter(r -> modelName.equalsIgnoreCase(r.modelName()))
                .findFirst()
                .orElse(null);
    }

    private RoutingQualityClassification classify(
            BenchmarkModelResult candidateResult,
            double bestScore,
            double tolerance,
            BenchmarkModelResult costEfficientResult) {

        if (candidateResult == null || candidateResult.qualityScore() == null) {
            return RoutingQualityClassification.NO_VALID_COMPARISON;
        }

        double score = candidateResult.qualityScore().totalScore();
        double regret = bestScore - score;

        if (regret <= 1e-6) {
            // Check if there is a much cheaper quality-equivalent model
            if (costEfficientResult != null && !candidateResult.modelName().equalsIgnoreCase(costEfficientResult.modelName())) {
                double candidateCost = candidateResult.estimatedCost() != null ? candidateResult.estimatedCost() : 0.0;
                double effCost = costEfficientResult.estimatedCost() != null ? costEfficientResult.estimatedCost() : 0.0;
                if (candidateCost > effCost * 1.5 && candidateCost - effCost > 0.01) {
                    return RoutingQualityClassification.POTENTIAL_OVER_ROUTING;
                }
            }
            return RoutingQualityClassification.OPTIMAL;
        }

        if (regret <= tolerance) {
            if (costEfficientResult != null && !candidateResult.modelName().equalsIgnoreCase(costEfficientResult.modelName())) {
                double candidateCost = candidateResult.estimatedCost() != null ? candidateResult.estimatedCost() : 0.0;
                double effCost = costEfficientResult.estimatedCost() != null ? costEfficientResult.estimatedCost() : 0.0;
                if (candidateCost > effCost * 1.5 && candidateCost - effCost > 0.01) {
                    return RoutingQualityClassification.POTENTIAL_OVER_ROUTING;
                }
            }
            return RoutingQualityClassification.QUALITY_EQUIVALENT;
        }

        return RoutingQualityClassification.UNDER_ROUTING;
    }
}
