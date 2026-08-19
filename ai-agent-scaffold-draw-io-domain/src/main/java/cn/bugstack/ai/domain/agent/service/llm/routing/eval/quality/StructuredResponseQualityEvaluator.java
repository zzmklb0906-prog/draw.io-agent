package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Deterministic quality evaluator for structured JSON generation and format compliance tasks.
 */
@Component
public class StructuredResponseQualityEvaluator implements ResponseQualityEvaluator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(BenchmarkCase benchmarkCase) {
        if (benchmarkCase == null || benchmarkCase.taskType() == null) return false;
        return benchmarkCase.taskType() == TaskType.STRUCTURED_GENERATION
                || (benchmarkCase.expected() != null && StringUtils.isNotBlank(benchmarkCase.expected().schemaJson()));
    }

    @Override
    public ModelQualityScore evaluate(BenchmarkCase benchmarkCase, BenchmarkRawResponse rawResponse) {
        if (rawResponse == null || !rawResponse.success() || StringUtils.isBlank(rawResponse.responseText())) {
            return ModelQualityScore.failed("Response was empty or execution failed: " + (rawResponse != null ? rawResponse.errorMessage() : "null"));
        }

        String text = rawResponse.responseText().trim();
        // Strip markdown code block if present
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        text = text.trim();

        List<String> issues = new ArrayList<>();
        Map<String, Double> dimensions = new LinkedHashMap<>();

        // 1. JSON Syntax Validity (40 pts)
        JsonNode rootNode = null;
        double syntaxScore = 0.0;
        try {
            rootNode = objectMapper.readTree(text);
            syntaxScore = 40.0;
        } catch (Exception e) {
            issues.add("JSON parsing failed: " + e.getMessage());
            dimensions.put("JSON_SYNTAX", 0.0);
            dimensions.put("FIELD_COMPLETENESS", 0.0);
            dimensions.put("FORMAT_COMPLIANCE", 0.0);
            return ModelQualityScore.of(0.0, dimensions, false, issues);
        }
        dimensions.put("JSON_SYNTAX", syntaxScore);

        // 2. Required Fields Completeness (40 pts)
        double completenessScore = 40.0;
        List<String> requiredFields = benchmarkCase.expected() != null ? benchmarkCase.expected().requiredFields() : List.of();
        if (!requiredFields.isEmpty() && rootNode != null) {
            int found = 0;
            for (String field : requiredFields) {
                if (rootNode.hasNonNull(field) || hasNestedField(rootNode, field)) {
                    found++;
                } else {
                    issues.add("Missing required field: " + field);
                }
            }
            completenessScore = 40.0 * ((double) found / requiredFields.size());
        }
        dimensions.put("FIELD_COMPLETENESS", completenessScore);

        // 3. Format Compliance & Cleanliness (20 pts)
        double complianceScore = 20.0;
        if (rawResponse.responseText().contains("```json") && !rawResponse.responseText().contains("Here is")) {
            complianceScore = 20.0;
        } else if (rawResponse.responseText().startsWith("{") || rawResponse.responseText().startsWith("[")) {
            complianceScore = 20.0;
        } else {
            complianceScore = 10.0;
            issues.add("Response contained conversational filler text around JSON structure");
        }
        dimensions.put("FORMAT_COMPLIANCE", complianceScore);

        double totalScore = syntaxScore + completenessScore + complianceScore;
        boolean passed = totalScore >= 70.0 && issues.isEmpty();

        return ModelQualityScore.of(totalScore, dimensions, passed, issues);
    }

    private boolean hasNestedField(JsonNode node, String fieldName) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getKey().equalsIgnoreCase(fieldName)) return true;
                if (hasNestedField(entry.getValue(), fieldName)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasNestedField(child, fieldName)) return true;
            }
        }
        return false;
    }
}
