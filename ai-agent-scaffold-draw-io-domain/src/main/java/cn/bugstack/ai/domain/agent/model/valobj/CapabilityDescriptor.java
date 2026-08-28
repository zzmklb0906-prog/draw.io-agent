package cn.bugstack.ai.domain.agent.model.valobj;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CapabilityDescriptor(String capabilityId, String type, String group, String name,
                                   String description, List<String> tags,
                                   List<String> aliases, List<String> examples, List<String> negativeExamples,
                                   String riskLevel, Map<String, Object> inputSchema, int version,
                                   String schemaVersion, String contentVersion) {
    public CapabilityDescriptor {
        tags = tags == null ? List.of() : List.copyOf(tags);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        examples = examples == null ? List.of() : List.copyOf(examples);
        negativeExamples = negativeExamples == null ? List.of() : List.copyOf(negativeExamples);
        inputSchema = inputSchema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }
}
