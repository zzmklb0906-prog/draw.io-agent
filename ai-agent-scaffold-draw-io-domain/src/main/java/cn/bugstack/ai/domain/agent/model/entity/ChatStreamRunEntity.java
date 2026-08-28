package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamRunEntity {

    private String runId;
    private String userId;
    private String sessionId;
    private String conversationId;
    private String agentId;
    private String checkpointId;
    private Long checkpointRevision;
    private String idempotencyKey;
    private String status;
    private Long lastSequenceNo;
    private String errorMessage;
    private Date createdAt;
    private Date updatedAt;

}
