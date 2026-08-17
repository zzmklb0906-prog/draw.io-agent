package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeObservationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LightweightMonitorServiceTest {

    @Test
    void shouldCarryExplicitParentRunAcrossNestedBranches() {
        IRuntimeObservationRepository repository=mock(IRuntimeObservationRepository.class);
        LightweightMonitorService monitor=new LightweightMonitorService();
        monitor.setPersistence(repository);
        monitor.runStarted("inv-tree","session-tree","admin","root");
        String root=monitor.agentStarted("inv-tree","root",null,"root",null);
        String child=monitor.agentStarted("inv-tree","child","root","root/parallel-a",null);
        verify(repository).agentStarted(eq("inv-tree"),eq(root),isNull(),eq("root"),eq("root"),anyLong());
        verify(repository).agentStarted(eq("inv-tree"),eq(child),eq(root),eq("child"),eq("root/parallel-a"),anyLong());
    }

    @Test
    void shouldFinalizeInvocationTokensToolsAndCompressionIdempotently() {
        LightweightMonitorService monitor = new LightweightMonitorService();
        monitor.runStarted("inv-1", "session-1", "admin", "workflow");
        monitor.agentStarted("inv-1", "agent_drawer");
        monitor.modelStarted("inv-1", "agent_drawer");
        monitor.usage("inv-1", "agent_drawer", 100, 25, 125);
        monitor.modelCompleted("inv-1", "agent_drawer");
        monitor.toolStarted("inv-1", "agent_drawer", "call-1", "load_skill");
        monitor.toolCompleted("inv-1", "call-1", true, "loaded");
        monitor.compression("inv-1", 89_600, 2_000, "ADK_LLM_EVENT_SUMMARIZER", 15);
        monitor.runCompleted("inv-1", true, "");
        monitor.runCompleted("inv-1", false, "late emitter error");

        Map<String, Object> summary = monitor.summary();
        assertEquals(1L, summary.get("success"));
        assertEquals(0L, summary.get("errors"));
        assertEquals(0L, summary.get("active"));
        assertEquals(100L, summary.get("inputTokens"));
        assertEquals(25L, summary.get("outputTokens"));

        Map<String, Object> detail = monitor.detail("inv-1");
        assertEquals("SUCCESS", detail.get("status"));
        assertEquals(1, ((List<?>) detail.get("tools")).size());
        assertEquals(1, ((List<?>) detail.get("compressions")).size());
    }

    @Test
    void shouldCloseInvocationWhenModelFailsBeforeFirstEvent() {
        IRuntimeObservationRepository repository=mock(IRuntimeObservationRepository.class);
        LightweightMonitorService monitor=new LightweightMonitorService();
        monitor.setPersistence(repository);
        monitor.runStarted("inv-error","session-error","admin","general_orchestrator");
        monitor.agentStarted("inv-error","general_orchestrator");
        monitor.modelStarted("inv-error","general_orchestrator");
        monitor.modelFailed("inv-error","general_orchestrator",new IllegalStateException("401"));

        assertEquals("ERROR",monitor.detail("inv-error").get("status"));
        assertEquals(0L,monitor.summary().get("active"));
        verify(repository).modelCompleted(eq("inv-error"),any(),eq("general_orchestrator"),anyLong(),anyLong(),anyLong(),anyLong(),eq("ERROR"),eq("401"));
        verify(repository).invocationCompleted(eq("inv-error"),eq("ERROR"),anyLong(),anyLong(),anyLong(),anyLong(),eq("401"));
    }
}
