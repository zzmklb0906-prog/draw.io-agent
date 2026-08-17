package cn.bugstack.ai.domain.agent.model.valobj;

import java.util.List;
import java.util.Map;

public record CapabilityDescriptor(String capabilityId, String type, String group, String name,
                                   String description, List<String> tags, String riskLevel,
                                   Map<String, Object> inputSchema, int version,
                                   String schemaVersion, String contentVersion) {
}
