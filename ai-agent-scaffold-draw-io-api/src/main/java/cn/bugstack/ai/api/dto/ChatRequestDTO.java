package cn.bugstack.ai.api.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String conversationId;
    private String message;
    private String checkpointId;
    private Long checkpointRevision;
    private String checkpointDecision;
    /** ADK adk_request_confirmation function-call id. */
    private String toolConfirmationCallId;
    private Boolean toolConfirmed;
    private java.util.Map<String,Object> toolConfirmationPayload;
    /** Stable across HTTP retries of the same user intent. */
    private String idempotencyKey;

    // 自定义配置
    private String customBaseUrl;
    private String customApiKey;
    private String customCompletionsPath;
    private String customModel;

}
