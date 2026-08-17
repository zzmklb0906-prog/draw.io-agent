package cn.bugstack.ai.domain.agent.model.entity;

import java.util.UUID;

public record WorkflowRecoveryJob(UUID jobId, String checkpointId, String sourceInvocationId,
                                  UUID conversationId, String username, String agentId,
                                  String sessionId, String stage, long checkpointRevision,
                                  String originalPrompt, String approvalJson) {}
