package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import java.util.List;

/**
 * Expected output criteria and evaluation constraints for a {@link BenchmarkCase}.
 */
public record BenchmarkExpectedOutput(
        String schemaJson,
        List<String> requiredFields,
        List<String> forbiddenTerms,
        List<String> requiredElements,
        List<String> expectedIssues,
        Integer minElementCount,
        Integer minEdgeCount,
        String preferredModel,
        Double humanExpectedScore
) {
    public BenchmarkExpectedOutput {
        requiredFields = requiredFields != null ? List.copyOf(requiredFields) : List.of();
        forbiddenTerms = forbiddenTerms != null ? List.copyOf(forbiddenTerms) : List.of();
        requiredElements = requiredElements != null ? List.copyOf(requiredElements) : List.of();
        expectedIssues = expectedIssues != null ? List.copyOf(expectedIssues) : List.of();
    }
}
