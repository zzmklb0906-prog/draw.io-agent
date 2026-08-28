package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IChatStreamRunRepository;
import cn.bugstack.ai.domain.agent.model.entity.ChatStreamEventEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatStreamRunEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ai.agent.persistence.mode", havingValue = "postgres", matchIfMissing = true)
public class PostgresChatStreamRunRepository implements IChatStreamRunRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ChatStreamRunEntity> RUN_ROW_MAPPER = (rs, rowNum) -> ChatStreamRunEntity.builder()
            .runId(rs.getString("run_id"))
            .userId(rs.getString("user_id"))
            .sessionId(rs.getString("session_id"))
            .conversationId(rs.getString("conversation_id"))
            .agentId(rs.getString("agent_id"))
            .checkpointId(rs.getString("checkpoint_id"))
            .checkpointRevision(rs.getObject("checkpoint_revision") == null ? null : rs.getLong("checkpoint_revision"))
            .idempotencyKey(rs.getString("idempotency_key"))
            .status(rs.getString("status"))
            .lastSequenceNo(rs.getLong("last_sequence_no"))
            .errorMessage(rs.getString("error_message"))
            .createdAt(rs.getTimestamp("created_at") == null ? null : new Date(rs.getTimestamp("created_at").getTime()))
            .updatedAt(rs.getTimestamp("updated_at") == null ? null : new Date(rs.getTimestamp("updated_at").getTime()))
            .build();

    private static final RowMapper<ChatStreamEventEntity> EVENT_ROW_MAPPER = (rs, rowNum) -> ChatStreamEventEntity.builder()
            .id(rs.getLong("id"))
            .runId(rs.getString("run_id"))
            .sequenceNo(rs.getLong("sequence_no"))
            .eventType(rs.getString("event_type"))
            .phase(rs.getString("phase"))
            .dataJson(rs.getString("data_json"))
            .createdAt(rs.getTimestamp("created_at") == null ? null : new Date(rs.getTimestamp("created_at").getTime()))
            .build();

    public PostgresChatStreamRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ChatStreamRunEntity save(ChatStreamRunEntity run) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp createdAt = run.getCreatedAt() != null ? new Timestamp(run.getCreatedAt().getTime()) : now;
        Timestamp updatedAt = run.getUpdatedAt() != null ? new Timestamp(run.getUpdatedAt().getTime()) : now;

        try {
            jdbc.update(
                    "INSERT INTO chat_stream_run (" +
                            "run_id, user_id, session_id, conversation_id, agent_id, " +
                            "checkpoint_id, checkpoint_revision, idempotency_key, status, " +
                            "last_sequence_no, error_message, created_at, updated_at" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT (run_id) DO UPDATE SET " +
                            "status = EXCLUDED.status, " +
                            "last_sequence_no = EXCLUDED.last_sequence_no, " +
                            "error_message = EXCLUDED.error_message, " +
                            "updated_at = EXCLUDED.updated_at",
                    run.getRunId(),
                    run.getUserId(),
                    run.getSessionId(),
                    run.getConversationId(),
                    run.getAgentId(),
                    run.getCheckpointId(),
                    run.getCheckpointRevision(),
                    run.getIdempotencyKey(),
                    run.getStatus(),
                    run.getLastSequenceNo() == null ? 0L : run.getLastSequenceNo(),
                    run.getErrorMessage(),
                    createdAt,
                    updatedAt
            );
            return run;
        } catch (DataIntegrityViolationException ex) {
            if (StringUtils.isNotBlank(run.getIdempotencyKey())) {
                Optional<ChatStreamRunEntity> existing = findByIdempotencyKey(run.getUserId(), run.getIdempotencyKey());
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
            throw ex;
        }
    }

    @Override
    public Optional<ChatStreamRunEntity> findById(String runId) {
        if (StringUtils.isBlank(runId)) return Optional.empty();
        List<ChatStreamRunEntity> list = jdbc.query(
                "SELECT run_id, user_id, session_id, conversation_id, agent_id, " +
                        "checkpoint_id, checkpoint_revision, idempotency_key, status, " +
                        "last_sequence_no, error_message, created_at, updated_at " +
                        "FROM chat_stream_run WHERE run_id = ?",
                RUN_ROW_MAPPER,
                runId
        );
        return list.stream().findFirst();
    }

    @Override
    public Optional<ChatStreamRunEntity> findByIdempotencyKey(String userId, String idempotencyKey) {
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(idempotencyKey)) return Optional.empty();
        List<ChatStreamRunEntity> list = jdbc.query(
                "SELECT run_id, user_id, session_id, conversation_id, agent_id, " +
                        "checkpoint_id, checkpoint_revision, idempotency_key, status, " +
                        "last_sequence_no, error_message, created_at, updated_at " +
                        "FROM chat_stream_run WHERE user_id = ? AND idempotency_key = ? " +
                        "ORDER BY created_at DESC LIMIT 1",
                RUN_ROW_MAPPER,
                userId,
                idempotencyKey
        );
        return list.stream().findFirst();
    }

    @Override
    public Optional<ChatStreamRunEntity> findActiveRun(String userId, String sessionId, String conversationId) {
        if (StringUtils.isBlank(userId)) return Optional.empty();
        if (StringUtils.isNotBlank(conversationId)) {
            List<ChatStreamRunEntity> list = jdbc.query(
                    "SELECT run_id, user_id, session_id, conversation_id, agent_id, " +
                            "checkpoint_id, checkpoint_revision, idempotency_key, status, " +
                            "last_sequence_no, error_message, created_at, updated_at " +
                            "FROM chat_stream_run WHERE user_id = ? AND conversation_id = ? AND status = 'RUNNING' " +
                            "ORDER BY updated_at DESC LIMIT 1",
                    RUN_ROW_MAPPER,
                    userId,
                    conversationId
            );
            if (!list.isEmpty()) return Optional.of(list.get(0));
        }
        if (StringUtils.isNotBlank(sessionId)) {
            List<ChatStreamRunEntity> list = jdbc.query(
                    "SELECT run_id, user_id, session_id, conversation_id, agent_id, " +
                            "checkpoint_id, checkpoint_revision, idempotency_key, status, " +
                            "last_sequence_no, error_message, created_at, updated_at " +
                            "FROM chat_stream_run WHERE user_id = ? AND session_id = ? AND status = 'RUNNING' " +
                            "ORDER BY updated_at DESC LIMIT 1",
                    RUN_ROW_MAPPER,
                    userId,
                    sessionId
            );
            return list.stream().findFirst();
        }
        return Optional.empty();
    }

    @Override
    public Optional<ChatStreamRunEntity> findLatestRun(String userId, String sessionId, String conversationId) {
        if (StringUtils.isBlank(userId)) return Optional.empty();
        if (StringUtils.isNotBlank(conversationId)) {
            List<ChatStreamRunEntity> list = jdbc.query(
                    "SELECT run_id, user_id, session_id, conversation_id, agent_id, " +
                            "checkpoint_id, checkpoint_revision, idempotency_key, status, " +
                            "last_sequence_no, error_message, created_at, updated_at " +
                            "FROM chat_stream_run WHERE user_id = ? AND conversation_id = ? " +
                            "ORDER BY updated_at DESC LIMIT 1",
                    RUN_ROW_MAPPER,
                    userId,
                    conversationId
            );
            if (!list.isEmpty()) return Optional.of(list.get(0));
        }
        if (StringUtils.isNotBlank(sessionId)) {
            List<ChatStreamRunEntity> list = jdbc.query(
                    "SELECT run_id, user_id, session_id, conversation_id, agent_id, " +
                            "checkpoint_id, checkpoint_revision, idempotency_key, status, " +
                            "last_sequence_no, error_message, created_at, updated_at " +
                            "FROM chat_stream_run WHERE user_id = ? AND session_id = ? " +
                            "ORDER BY updated_at DESC LIMIT 1",
                    RUN_ROW_MAPPER,
                    userId,
                    sessionId
            );
            return list.stream().findFirst();
        }
        return Optional.empty();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long appendEvent(String runId, String eventType, String phase, String dataJson) {
        List<Long> seqs = jdbc.query(
                "UPDATE chat_stream_run SET last_sequence_no = last_sequence_no + 1, updated_at = NOW() WHERE run_id = ? RETURNING last_sequence_no",
                (rs, rowNum) -> rs.getLong(1),
                runId
        );
        if (seqs.isEmpty()) {
            throw new AppException("RUN_NOT_FOUND", "Run " + runId + " 不存在");
        }
        long seq = seqs.get(0);
        jdbc.update(
                "INSERT INTO chat_stream_event (run_id, sequence_no, event_type, phase, data_json, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, NOW())",
                runId,
                seq,
                StringUtils.defaultIfBlank(eventType, "message"),
                StringUtils.defaultIfBlank(phase, "thinking"),
                dataJson == null ? "{}" : dataJson
        );
        return seq;
    }

    @Override
    public List<ChatStreamEventEntity> queryEventsAfter(String runId, long afterSequenceNo, int limit) {
        int effectiveLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return jdbc.query(
                "SELECT id, run_id, sequence_no, event_type, phase, data_json, created_at " +
                        "FROM chat_stream_event WHERE run_id = ? AND sequence_no > ? " +
                        "ORDER BY sequence_no ASC LIMIT ?",
                EVENT_ROW_MAPPER,
                runId,
                afterSequenceNo,
                effectiveLimit
        );
    }

    @Override
    public void updateStatus(String runId, String status, String errorMessage) {
        jdbc.update(
                "UPDATE chat_stream_run SET status = ?, error_message = ?, updated_at = NOW() WHERE run_id = ?",
                status,
                errorMessage,
                runId
        );
    }

}
