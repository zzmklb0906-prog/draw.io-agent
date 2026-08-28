package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamRunResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String runId;
    private String agentId;
    private String userId;
    private String sessionId;
    private String conversationId;
    private String checkpointId;
    private Long checkpointRevision;
    private String status;
    private Long lastSequenceNo;
    private String errorMessage;
    private Long createdAt;
    private Long updatedAt;

}
