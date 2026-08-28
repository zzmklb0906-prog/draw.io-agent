package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ChatStreamEventEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatStreamRunEntity;

import java.util.List;
import java.util.Optional;

public interface IChatStreamRunRepository {

    ChatStreamRunEntity save(ChatStreamRunEntity run);

    Optional<ChatStreamRunEntity> findById(String runId);

    Optional<ChatStreamRunEntity> findByIdempotencyKey(String userId, String idempotencyKey);

    Optional<ChatStreamRunEntity> findActiveRun(String userId, String sessionId, String conversationId);

    Optional<ChatStreamRunEntity> findLatestRun(String userId, String sessionId, String conversationId);

    long appendEvent(String runId, String eventType, String phase, String dataJson);

    List<ChatStreamEventEntity> queryEventsAfter(String runId, long afterSequenceNo, int limit);

    void updateStatus(String runId, String status, String errorMessage);

}
