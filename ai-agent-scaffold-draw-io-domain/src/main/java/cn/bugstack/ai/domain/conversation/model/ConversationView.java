package cn.bugstack.ai.domain.conversation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationView(UUID id, String agentId, String sessionId, String title, String status,
                               String currentInvocationId, String checkpointId, long checkpointRevision,
                               Instant createdAt, Instant updatedAt,
                               long messageCount, String activeToolName, Instant activeToolStartedAt,
                               String activeToolStatus, List<MessageView> messages) {
    public record MessageView(UUID id, long sequence, String role, String type, String content,
                              String contentJson, String invocationId, Instant createdAt) {}
}
