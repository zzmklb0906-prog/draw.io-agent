package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowCheckpointEntity {
    private String checkpointId;
    private String invocationId;
    private String agentId;
    private String userId;
    private String sessionId;
    private String status;
    private String stage;
    private String originalPrompt;
    private String approvalJson;
    private String pendingToolCallId;
    private String pendingToolConfirmationJson;
    private String errorMessage;
    private long revision;
    private long createdAt;
    private long updatedAt;
}
