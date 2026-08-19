package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

/**
 * Detailed routing quality evaluation for a single benchmark case comparing Dynamic and Legacy routers against actual response quality.
 */
public record RoutingQualityEvaluation(
        String caseId,
        String dynamicRecommendedModel,
        String legacyModel,
        String bestQualityModel,
        String costEfficientBestModel,
        Double dynamicQualityScore,
        Double legacyQualityScore,
        Double bestQualityScore,
        Double dynamicRegret,
        Double legacyRegret,
        RoutingQualityClassification dynamicClassification,
        RoutingQualityClassification legacyClassification
) {}
