package cn.bugstack.ai.domain.agent.memory.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MemoryEvidenceEntity {
    private String memoryId;
    private String evidenceType;
    private String evidenceId;
    private long createdAt;
}
