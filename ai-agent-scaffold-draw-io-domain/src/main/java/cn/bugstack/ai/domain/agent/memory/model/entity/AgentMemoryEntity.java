package cn.bugstack.ai.domain.agent.memory.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentMemoryEntity {
    private String memoryId;
    private String userId;
    private String projectId;
    private String memoryType;
    private String content;
    private String structuredData;
    private double importance;
    private double confidence;
    private boolean confirmed;
    private String sourceSessionId;
    private String sourceEventId;
    private long createdAt;
    private long updatedAt;
    private Long expiresAt;
    private boolean deleted;
}
