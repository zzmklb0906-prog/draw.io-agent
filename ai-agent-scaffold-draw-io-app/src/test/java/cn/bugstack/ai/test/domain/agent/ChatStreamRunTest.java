package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IChatStreamRunRepository;
import cn.bugstack.ai.domain.agent.model.entity.ChatStreamEventEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatStreamRunEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ChatStreamRunTest {

    private InMemoryChatStreamRunRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryChatStreamRunRepository();
    }

    @Test
    void testCreateAndFindRun() {
        String runId = UUID.randomUUID().toString();
        ChatStreamRunEntity run = ChatStreamRunEntity.builder()
                .runId(runId)
                .userId("user_1")
                .sessionId("session_1")
                .conversationId("conv_1")
                .agentId("300000")
                .checkpointId("cp_1")
                .checkpointRevision(1L)
                .idempotencyKey("idem_1")
                .status("RUNNING")
                .lastSequenceNo(0L)
                .createdAt(new Date())
                .updatedAt(new Date())
                .build();

        repository.save(run);

        Optional<ChatStreamRunEntity> found = repository.findById(runId);
        assertTrue(found.isPresent());
        assertEquals("user_1", found.get().getUserId());
        assertEquals("RUNNING", found.get().getStatus());
        assertEquals(0L, found.get().getLastSequenceNo());
    }

    @Test
    void testIdempotencyLookup() {
        String runId = UUID.randomUUID().toString();
        ChatStreamRunEntity run = ChatStreamRunEntity.builder()
                .runId(runId)
                .userId("user_1")
                .sessionId("session_1")
                .conversationId("conv_1")
                .agentId("300000")
                .idempotencyKey("idem_key_abc")
                .status("RUNNING")
                .lastSequenceNo(0L)
                .createdAt(new Date())
                .updatedAt(new Date())
                .build();
        repository.save(run);

        Optional<ChatStreamRunEntity> byKey = repository.findByIdempotencyKey("user_1", "idem_key_abc");
        assertTrue(byKey.isPresent());
        assertEquals(runId, byKey.get().getRunId());

        // Different user with same key should not find it
        Optional<ChatStreamRunEntity> otherUser = repository.findByIdempotencyKey("user_2", "idem_key_abc");
        assertFalse(otherUser.isPresent());
    }

    @Test
    void testActiveRunLookup() {
        String runId1 = UUID.randomUUID().toString();
        ChatStreamRunEntity run1 = ChatStreamRunEntity.builder()
                .runId(runId1)
                .userId("user_1")
                .sessionId("session_1")
                .conversationId("conv_1")
                .status("RUNNING")
                .createdAt(new Date(System.currentTimeMillis() - 10000))
                .updatedAt(new Date(System.currentTimeMillis() - 10000))
                .build();
        repository.save(run1);

        Optional<ChatStreamRunEntity> active = repository.findActiveRun("user_1", "session_1", "conv_1");
        assertTrue(active.isPresent());
        assertEquals(runId1, active.get().getRunId());

        // Mark as completed
        repository.updateStatus(runId1, "COMPLETED", null);
        Optional<ChatStreamRunEntity> activeAfterComplete = repository.findActiveRun("user_1", "session_1", "conv_1");
        assertFalse(activeAfterComplete.isPresent());
    }

    @Test
    void testAtomicSequenceAndOrderedCursorReplay() {
        String runId = UUID.randomUUID().toString();
        ChatStreamRunEntity run = ChatStreamRunEntity.builder()
                .runId(runId)
                .userId("user_1")
                .sessionId("session_1")
                .status("RUNNING")
                .lastSequenceNo(0L)
                .createdAt(new Date())
                .updatedAt(new Date())
                .build();
        repository.save(run);

        long seq1 = repository.appendEvent(runId, "checkpoint", "thinking", "{\"type\":\"checkpoint\"}");
        long seq2 = repository.appendEvent(runId, "token", "thinking", "{\"type\":\"token\",\"content\":\"a\"}");
        long seq3 = repository.appendEvent(runId, "token", "thinking", "{\"type\":\"token\",\"content\":\"b\"}");
        long seq4 = repository.appendEvent(runId, "done", "done", "{\"type\":\"done\"}");

        assertEquals(1L, seq1);
        assertEquals(2L, seq2);
        assertEquals(3L, seq3);
        assertEquals(4L, seq4);

        // Replay all events after cursor 0
        List<ChatStreamEventEntity> all = repository.queryEventsAfter(runId, 0L, 100);
        assertEquals(4, all.size());
        assertEquals(1L, all.get(0).getSequenceNo());
        assertEquals(2L, all.get(1).getSequenceNo());
        assertEquals(3L, all.get(2).getSequenceNo());
        assertEquals(4L, all.get(3).getSequenceNo());

        // Replay after cursor 2 (e.g. on reconnect)
        List<ChatStreamEventEntity> resumed = repository.queryEventsAfter(runId, 2L, 100);
        assertEquals(2, resumed.size());
        assertEquals(3L, resumed.get(0).getSequenceNo());
        assertEquals(4L, resumed.get(1).getSequenceNo());

        // Replay after cursor 4
        List<ChatStreamEventEntity> empty = repository.queryEventsAfter(runId, 4L, 100);
        assertTrue(empty.isEmpty());
    }

    @Test
    void testConcurrentAppendEventSequenceMonotonicity() throws Exception {
        String runId = UUID.randomUUID().toString();
        ChatStreamRunEntity run = ChatStreamRunEntity.builder()
                .runId(runId)
                .userId("user_1")
                .sessionId("session_1")
                .status("RUNNING")
                .lastSequenceNo(0L)
                .createdAt(new Date())
                .updatedAt(new Date())
                .build();
        repository.save(run);

        int threadCount = 20;
        int eventsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Long> generatedSeqs = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < eventsPerThread; j++) {
                        long seq = repository.appendEvent(runId, "token", "thinking", "{\"token\":\"tok\"}");
                        generatedSeqs.add(seq);
                    }
                } catch (Exception e) {
                    fail(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * eventsPerThread, generatedSeqs.size());
        Set<Long> uniqueSeqs = new HashSet<>(generatedSeqs);
        assertEquals(threadCount * eventsPerThread, uniqueSeqs.size());

        Optional<ChatStreamRunEntity> finalRun = repository.findById(runId);
        assertTrue(finalRun.isPresent());
        assertEquals(threadCount * eventsPerThread, finalRun.get().getLastSequenceNo());
    }

    @Test
    void testTerminalStatusAndErrorRecording() {
        String runId = UUID.randomUUID().toString();
        ChatStreamRunEntity run = ChatStreamRunEntity.builder()
                .runId(runId)
                .userId("user_1")
                .sessionId("session_1")
                .status("RUNNING")
                .lastSequenceNo(0L)
                .createdAt(new Date())
                .updatedAt(new Date())
                .build();
        repository.save(run);

        repository.updateStatus(runId, "FAILED", "ADK Execution Timeout");
        Optional<ChatStreamRunEntity> failedRun = repository.findById(runId);
        assertTrue(failedRun.isPresent());
        assertEquals("FAILED", failedRun.get().getStatus());
        assertEquals("ADK Execution Timeout", failedRun.get().getErrorMessage());
    }

    public static class InMemoryChatStreamRunRepository implements IChatStreamRunRepository {
        private final Map<String, ChatStreamRunEntity> runs = new ConcurrentHashMap<>();
        private final Map<String, List<ChatStreamEventEntity>> events = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

        @Override
        public ChatStreamRunEntity save(ChatStreamRunEntity run) {
            runs.put(run.getRunId(), cloneRun(run));
            seqCounters.putIfAbsent(run.getRunId(), new AtomicLong(run.getLastSequenceNo() != null ? run.getLastSequenceNo() : 0L));
            events.putIfAbsent(run.getRunId(), new CopyOnWriteArrayList<>());
            return cloneRun(run);
        }

        @Override
        public Optional<ChatStreamRunEntity> findById(String runId) {
            ChatStreamRunEntity entity = runs.get(runId);
            return Optional.ofNullable(cloneRun(entity));
        }

        @Override
        public Optional<ChatStreamRunEntity> findByIdempotencyKey(String userId, String idempotencyKey) {
            if (userId == null || idempotencyKey == null) return Optional.empty();
            return runs.values().stream()
                    .filter(r -> userId.equals(r.getUserId()) && idempotencyKey.equals(r.getIdempotencyKey()))
                    .sorted((a, b) -> Long.compare(
                            b.getCreatedAt() != null ? b.getCreatedAt().getTime() : 0L,
                            a.getCreatedAt() != null ? a.getCreatedAt().getTime() : 0L))
                    .findFirst()
                    .map(this::cloneRun);
        }

        @Override
        public Optional<ChatStreamRunEntity> findActiveRun(String userId, String sessionId, String conversationId) {
            if (userId == null) return Optional.empty();
            return runs.values().stream()
                    .filter(r -> userId.equals(r.getUserId())
                            && "RUNNING".equals(r.getStatus())
                            && ((conversationId != null && conversationId.equals(r.getConversationId()))
                            || (sessionId != null && sessionId.equals(r.getSessionId()))))
                    .sorted((a, b) -> Long.compare(
                            b.getUpdatedAt() != null ? b.getUpdatedAt().getTime() : 0L,
                            a.getUpdatedAt() != null ? a.getUpdatedAt().getTime() : 0L))
                    .findFirst()
                    .map(this::cloneRun);
        }

        @Override
        public Optional<ChatStreamRunEntity> findLatestRun(String userId, String sessionId, String conversationId) {
            if (userId == null) return Optional.empty();
            return runs.values().stream()
                    .filter(r -> userId.equals(r.getUserId())
                            && ((conversationId != null && conversationId.equals(r.getConversationId()))
                            || (sessionId != null && sessionId.equals(r.getSessionId()))))
                    .sorted((a, b) -> Long.compare(
                            b.getUpdatedAt() != null ? b.getUpdatedAt().getTime() : 0L,
                            a.getUpdatedAt() != null ? a.getUpdatedAt().getTime() : 0L))
                    .findFirst()
                    .map(this::cloneRun);
        }

        @Override
        public long appendEvent(String runId, String eventType, String phase, String dataJson) {
            ChatStreamRunEntity run = runs.get(runId);
            if (run == null) throw new AppException("RUN_NOT_FOUND", "Run " + runId + " not found");

            AtomicLong counter = seqCounters.get(runId);
            long seq = counter.incrementAndGet();
            run.setLastSequenceNo(seq);
            run.setUpdatedAt(new Date());

            ChatStreamEventEntity event = ChatStreamEventEntity.builder()
                    .id(seq)
                    .runId(runId)
                    .sequenceNo(seq)
                    .eventType(eventType != null ? eventType : "message")
                    .phase(phase != null ? phase : "thinking")
                    .dataJson(dataJson != null ? dataJson : "{}")
                    .createdAt(new Date())
                    .build();
            events.get(runId).add(event);
            return seq;
        }

        @Override
        public List<ChatStreamEventEntity> queryEventsAfter(String runId, long afterSequenceNo, int limit) {
            List<ChatStreamEventEntity> list = events.getOrDefault(runId, Collections.emptyList());
            int effectiveLimit = limit <= 0 ? 100 : Math.min(limit, 500);
            return list.stream()
                    .filter(e -> e.getSequenceNo() > afterSequenceNo)
                    .sorted(Comparator.comparingLong(ChatStreamEventEntity::getSequenceNo))
                    .limit(effectiveLimit)
                    .collect(Collectors.toList());
        }

        @Override
        public void updateStatus(String runId, String status, String errorMessage) {
            ChatStreamRunEntity run = runs.get(runId);
            if (run != null) {
                run.setStatus(status);
                run.setErrorMessage(errorMessage);
                run.setUpdatedAt(new Date());
            }
        }

        private ChatStreamRunEntity cloneRun(ChatStreamRunEntity src) {
            if (src == null) return null;
            return ChatStreamRunEntity.builder()
                    .runId(src.getRunId())
                    .userId(src.getUserId())
                    .sessionId(src.getSessionId())
                    .conversationId(src.getConversationId())
                    .agentId(src.getAgentId())
                    .checkpointId(src.getCheckpointId())
                    .checkpointRevision(src.getCheckpointRevision())
                    .idempotencyKey(src.getIdempotencyKey())
                    .status(src.getStatus())
                    .lastSequenceNo(src.getLastSequenceNo())
                    .errorMessage(src.getErrorMessage())
                    .createdAt(src.getCreatedAt())
                    .updatedAt(src.getUpdatedAt())
                    .build();
        }
    }
}
