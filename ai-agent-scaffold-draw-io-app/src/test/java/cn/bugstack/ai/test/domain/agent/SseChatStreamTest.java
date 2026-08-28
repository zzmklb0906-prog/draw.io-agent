package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.model.entity.ChatStreamEventEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatStreamRunEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import cn.bugstack.ai.trigger.http.AgentServiceController;

import static org.junit.jupiter.api.Assertions.*;

public class SseChatStreamTest {

    private ChatStreamRunTest.InMemoryChatStreamRunRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChatStreamRunTest.InMemoryChatStreamRunRepository();
    }

    @Test
    void testCursorParsingPreference() {
        // Last-Event-ID header takes precedence when present and valid
        assertEquals(42L, AgentServiceController.resolveCursor(10L, " 42 "));

        // When Last-Event-ID is absent or blank, fallback to after param
        assertEquals(10L, AgentServiceController.resolveCursor(10L, null));
        assertEquals(10L, AgentServiceController.resolveCursor(10L, "   "));
    }

    @Test
    void testSseSubscriberReplayAndTerminalCompletion() {
        String runId = UUID.randomUUID().toString();
        ChatStreamRunEntity run = ChatStreamRunEntity.builder()
                .runId(runId)
                .userId("user_test")
                .sessionId("session_test")
                .status("RUNNING")
                .lastSequenceNo(0L)
                .createdAt(new Date())
                .updatedAt(new Date())
                .build();
        repository.save(run);

        repository.appendEvent(runId, "checkpoint", "thinking", "{\"phase\":\"thinking\",\"chunk\":{\"type\":\"checkpoint\"}}");
        repository.appendEvent(runId, "token", "drawing", "{\"phase\":\"drawing\",\"chunk\":{\"type\":\"token\",\"content\":\"node\"}}");
        repository.appendEvent(runId, "done", "done", "{\"phase\":\"done\",\"chunk\":{\"type\":\"done\"}}");
        repository.updateStatus(runId, "COMPLETED", null);

        SseEmitter emitter = new SseEmitter(60000L);
        AtomicLong cursor = new AtomicLong(0L);
        List<ChatStreamEventEntity> delivered = new ArrayList<>();

        // Simulate subscriber query after cursor
        List<ChatStreamEventEntity> events = repository.queryEventsAfter(runId, cursor.get(), 100);
        assertEquals(3, events.size());

        for (ChatStreamEventEntity event : events) {
            delivered.add(event);
            cursor.set(event.getSequenceNo());
        }

        assertEquals(3, delivered.size());
        assertEquals("checkpoint", delivered.get(0).getEventType());
        assertEquals("token", delivered.get(1).getEventType());
        assertEquals("done", delivered.get(2).getEventType());

        // Verify terminal status detected
        Optional<ChatStreamRunEntity> runOpt = repository.findById(runId);
        assertTrue(runOpt.isPresent());
        assertEquals("COMPLETED", runOpt.get().getStatus());
        assertTrue(cursor.get() >= runOpt.get().getLastSequenceNo());
        emitter.complete();
    }

    @Test
    void testHeartbeatIntervalTracking() {
        AtomicLong lastHeartbeatAt = new AtomicLong(System.currentTimeMillis() - 20000);
        long now = System.currentTimeMillis();
        boolean shouldHeartbeat = (now - lastHeartbeatAt.get()) >= 15000;
        assertTrue(shouldHeartbeat);

        lastHeartbeatAt.set(now);
        boolean shouldNotHeartbeat = (System.currentTimeMillis() - lastHeartbeatAt.get()) >= 15000;
        assertFalse(shouldNotHeartbeat);
    }

    @Test
    void testMalformedAndNegativeCursorFallback() {
        // Negative Last-Event-ID should be ignored, fallback to valid after
        assertEquals(15L, AgentServiceController.resolveCursor(15L, "-5"));

        // Malformed Last-Event-ID should be ignored, fallback to valid after
        assertEquals(15L, AgentServiceController.resolveCursor(15L, "not-a-number"));

        // Malformed Last-Event-ID and negative after should both be ignored, fallback to 0
        assertEquals(0L, AgentServiceController.resolveCursor(-10L, "not-a-number"));

        // Null after and null lastEventId should fallback to 0
        assertEquals(0L, AgentServiceController.resolveCursor(null, null));
    }
}
