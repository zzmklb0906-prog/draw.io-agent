package cn.bugstack.ai.domain.conversation.adapter;

import cn.bugstack.ai.domain.conversation.model.ConversationView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IConversationRepository {
    ConversationView create(String username, String agentId, String sessionId, String title);
    List<ConversationView> list(String username, int limit);
    Optional<ConversationView> get(String username, UUID conversationId);
    ConversationView.MessageView append(String username, UUID conversationId, String role, String type,
                                         String content, String contentJson, String invocationId);
    default ConversationView.MessageView append(String username, UUID conversationId, String role, String type,
                                                 String content, String contentJson, String invocationId,
                                                 String idempotencyKey) {
        return append(username, conversationId, role, type, content, contentJson, invocationId);
    }
    void updateStatus(String username, UUID conversationId, String status, String invocationId);
    void delete(String username, UUID conversationId);
}
