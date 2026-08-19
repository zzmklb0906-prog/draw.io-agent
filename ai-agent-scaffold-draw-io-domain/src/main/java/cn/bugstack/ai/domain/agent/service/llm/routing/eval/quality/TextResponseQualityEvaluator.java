package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Deterministic, rule-based quality evaluator for text tasks (EXTRACT, SUMMARIZE, DIAGNOSE, ANALYZE, GENERAL_CHAT, CODE_GENERATION, SIMPLE_EDIT).
 */
@Component
public class TextResponseQualityEvaluator implements ResponseQualityEvaluator {

    @Override
    public boolean supports(BenchmarkCase benchmarkCase) {
        // Fallback evaluator supporting any benchmark case
        return benchmarkCase != null;
    }

    @Override
    public ModelQualityScore evaluate(BenchmarkCase benchmarkCase, BenchmarkRawResponse rawResponse) {
        if (rawResponse == null || !rawResponse.success() || StringUtils.isBlank(rawResponse.responseText())) {
            return ModelQualityScore.failed("Response was empty or execution failed: " + (rawResponse != null ? rawResponse.errorMessage() : "null"));
        }

        String text = rawResponse.responseText().trim();
        List<String> issues = new ArrayList<>();
        Map<String, Double> dimensions = new LinkedHashMap<>();

        // 1. Basic Validity & Non-empty (20 pts)
        double validityScore = text.length() >= 5 ? 20.0 : 5.0;
        if (text.length() < 5) issues.add("Response text is excessively short or trivial");
        dimensions.put("VALIDITY", validityScore);

        // 2. Expected Fields / Facts Coverage (50 pts)
        double coverageScore = 50.0;
        BenchmarkExpectedOutput expected = benchmarkCase.expected();
        if (expected != null) {
            List<String> reqFields = expected.requiredFields();
            if (reqFields.isEmpty() && expected.requiredElements() != null) {
                reqFields = expected.requiredElements();
            }

            if (!reqFields.isEmpty()) {
                String textLower = text.toLowerCase();
                int found = 0;
                for (String req : reqFields) {
                    if (textLower.contains(req.toLowerCase())) {
                        found++;
                    } else {
                        issues.add("Missing expected concept/fact: " + req);
                    }
                }
                coverageScore = 50.0 * ((double) found / reqFields.size());
            }
        }
        dimensions.put("FACT_COVERAGE", coverageScore);

        // 3. Forbidden Claims / Anti-patterns (30 pts)
        double forbiddenScore = 30.0;
        if (expected != null && expected.forbiddenTerms() != null && !expected.forbiddenTerms().isEmpty()) {
            String textLower = text.toLowerCase();
            for (String forbidden : expected.forbiddenTerms()) {
                if (textLower.contains(forbidden.toLowerCase())) {
                    forbiddenScore -= (30.0 / expected.forbiddenTerms().size());
                    issues.add("Found forbidden term/incorrect claim: " + forbidden);
                }
            }
        }
        dimensions.put("FACT_ACCURACY", Math.max(0.0, forbiddenScore));

        double totalScore = validityScore + coverageScore + Math.max(0.0, forbiddenScore);
        boolean passed = totalScore >= 70.0 && issues.isEmpty();

        return ModelQualityScore.of(totalScore, dimensions, passed, issues);
    }
}
