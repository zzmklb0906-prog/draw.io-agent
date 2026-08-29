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

    @Test
    void shouldRedactModelFailureMessageBeforePersistenceAndCompletion() {
        IRuntimeObservationRepository repository = mock(IRuntimeObservationRepository.class);
        LightweightMonitorService monitor = new LightweightMonitorService();
        monitor.setPersistence(repository);
        monitor.runStarted("inv-sec", "session-sec", "admin", "agent_sec");
        monitor.agentStarted("inv-sec", "agent_sec");
        monitor.modelStarted("inv-sec", "agent_sec");

        monitor.modelFailed("inv-sec", "agent_sec", new RuntimeException("Upstream failed with api_key: sk-topsecret999 and token=secret_abc"));

        Map<String, Object> detail = monitor.detail("inv-sec");
        org.junit.jupiter.api.Assertions.assertEquals("ERROR", detail.get("status"));
        String error = (String) detail.get("error");
        org.junit.jupiter.api.Assertions.assertTrue(error.contains("api_key: ***"));
        org.junit.jupiter.api.Assertions.assertTrue(error.contains("token=***"));
        org.junit.jupiter.api.Assertions.assertFalse(error.contains("sk-topsecret999"));
        org.junit.jupiter.api.Assertions.assertFalse(error.contains("secret_abc"));

        verify(repository).modelCompleted(eq("inv-sec"), any(), eq("agent_sec"), anyLong(), anyLong(), anyLong(), anyLong(), eq("ERROR"), argThat(msg -> msg.contains("***") && !msg.contains("sk-topsecret999")));
        verify(repository).invocationCompleted(eq("inv-sec"), eq("ERROR"), anyLong(), anyLong(), anyLong(), anyLong(), argThat(msg -> msg.contains("***") && !msg.contains("sk-topsecret999")));
    }

    @Test
    void shouldRedactCapabilityResultSummaryAndErrorWhileRetainingByteSizeAndHash() {
        IRuntimeObservationRepository repository = mock(IRuntimeObservationRepository.class);
        LightweightMonitorService monitor = new LightweightMonitorService();
        monitor.setPersistence(repository);
        monitor.runStarted("inv-cap", "session-cap", "admin", "agent_cap");

        cn.bugstack.ai.domain.agent.model.valobj.CapabilityDescriptor descriptor =
                new cn.bugstack.ai.domain.agent.model.valobj.CapabilityDescriptor(
                        "cap-1", "TOOL", "core", "test-tool",
                        "test tool description", List.of(), List.of(), List.of(), List.of(),
                        "LOW", Map.of(), 1, "1.0", "1.0");
        Map<String, Object> sensitiveResult = Map.of(
                "token", "secret-token-12345",
                "nested", Map.of("password", "p@ssword", "data", "normal-data"),
                "text", "call with api-key: hidden-key"
        );
        String rawJson = com.alibaba.fastjson.JSON.toJSONString(sensitiveResult);
        long rawBytes = rawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        monitor.capabilityCompleted("inv-cap", "exec-1", "EXECUTE", descriptor, System.currentTimeMillis() - 100, true, sensitiveResult, new RuntimeException("auth error with password: pass123"));

        Map<String, Object> detail = monitor.detail("inv-cap");
        List<Map<String, Object>> capEvents = (List<Map<String, Object>>) detail.get("capabilityEvents");
        org.junit.jupiter.api.Assertions.assertEquals(1, capEvents.size());
        Map<String, Object> event = capEvents.get(0);

        String summary = (String) event.get("resultSummary");
        String errorMsg = (String) event.get("error");
        org.junit.jupiter.api.Assertions.assertEquals(rawBytes, ((Number) event.get("resultSize")).longValue());
        org.junit.jupiter.api.Assertions.assertFalse(summary.contains("secret-token-12345"));
        org.junit.jupiter.api.Assertions.assertFalse(summary.contains("p@ssword"));
        org.junit.jupiter.api.Assertions.assertFalse(summary.contains("hidden-key"));
        org.junit.jupiter.api.Assertions.assertFalse(errorMsg.contains("pass123"));

        verify(repository).capabilityExecutionCompleted(
                eq("exec-1"), eq("SUCCESS"), anyLong(), anyLong(),
                argThat(s -> !s.contains("secret-token-12345") && !s.contains("p@ssword") && !s.contains("hidden-key")),
                eq(rawBytes),
                anyString(),
                eq(0),
                argThat(err -> !err.contains("pass123"))
        );
    }

    @Test
    void shouldRedactRuntimeEventPayloadRecursively() {
        IRuntimeObservationRepository repository = mock(IRuntimeObservationRepository.class);
        LightweightMonitorService monitor = new LightweightMonitorService();
        monitor.setPersistence(repository);
        monitor.runStarted("inv-rt", "session-rt", "admin", "agent_rt");

        monitor.modelRouted("inv-rt", "agent_rt", "gpt-4", "reason", 1, false,
                "routing with token: tok-999",
                Map.of("apiKey", "key-secret-abc", "nested", Map.of("password", "supersecret")),
                List.of(), List.of());

        verify(repository).runtimeEvent(eq("session-rt"), eq("inv-rt"), eq("MODEL_ROUTED"), argThat(payload -> {
            Map<String, Object> map = (Map<String, Object>) payload;
            Map<String, Object> metrics = (Map<String, Object>) map.get("metrics");
            String narrative = (String) map.get("narrative");
            boolean metricsClean = "***".equals(metrics.get("apiKey")) && "***".equals(((Map<?, ?>) metrics.get("nested")).get("password"));
            boolean narrativeClean = narrative.contains("token: ***") && !narrative.contains("tok-999");
            return metricsClean && narrativeClean;
        }));
    }

    @Test
    void shouldAccumulateTokensAcrossSequentialModelCallsAndPersistOnlyDeltas() {
        IRuntimeObservationRepository repository = mock(IRuntimeObservationRepository.class);
        LightweightMonitorService monitor = new LightweightMonitorService();
        monitor.setPersistence(repository);

        monitor.runStarted("inv-multi", "sess-1", "admin", "agent_drawer");
        String runId = monitor.agentStarted("inv-multi", "agent_drawer", null, "", null);

        // Call 1: 100 in, 20 out, 120 total
        monitor.modelStarted("inv-multi", "agent_drawer", runId);
        monitor.usage("inv-multi", "agent_drawer", 100, 20, 120);
        monitor.modelCompleted("inv-multi", "agent_drawer", runId);

        // Call 2: 50 in, 10 out, 60 total
        monitor.modelStarted("inv-multi", "agent_drawer", runId);
        monitor.usage("inv-multi", "agent_drawer", 50, 10, 60);
        monitor.modelCompleted("inv-multi", "agent_drawer", runId);

        // Verify in-memory totals accumulated (150 in, 30 out, 180 total)
        assertEquals(150L, monitor.summary().get("inputTokens"));
        assertEquals(30L, monitor.summary().get("outputTokens"));
        assertEquals(180L, monitor.summary().get("totalTokens"));

        Map<String, Object> detail = monitor.detail("inv-multi");
        assertEquals(150L, detail.get("inputTokens"));
        assertEquals(30L, detail.get("outputTokens"));
        assertEquals(180L, detail.get("totalTokens"));

        // Verify persistence received model-call deltas 100/20 then 50/10, NEVER cumulative 150/30
        verify(repository).modelCompleted(eq("inv-multi"), eq(runId), eq("agent_drawer"), anyLong(), anyLong(), eq(100L), eq(20L), eq("SUCCESS"), anyString());
        verify(repository).modelCompleted(eq("inv-multi"), eq(runId), eq("agent_drawer"), anyLong(), anyLong(), eq(50L), eq(10L), eq("SUCCESS"), anyString());
        verify(repository, never()).modelCompleted(eq("inv-multi"), eq(runId), eq("agent_drawer"), anyLong(), anyLong(), eq(150L), eq(30L), eq("SUCCESS"), anyString());

        // Agent and invocation completion receive cumulative totals 150/30
        monitor.agentCompleted("inv-multi", "agent_drawer", runId, "");
        verify(repository).agentCompleted(eq("inv-multi"), eq(runId), eq("agent_drawer"), anyLong(), anyLong(), eq(2L), anyLong(), eq(150L), eq(30L));

        monitor.runCompleted("inv-multi", true, "");
        verify(repository).invocationCompleted(eq("inv-multi"), eq("SUCCESS"), anyLong(), anyLong(), eq(150L), eq(30L), eq(""));
    }

    @Test
    void shouldReplaceEstimatedTokensOnFirstProviderUsageWithoutAddingThem() {
        IRuntimeObservationRepository repository = mock(IRuntimeObservationRepository.class);
        LightweightMonitorService monitor = new LightweightMonitorService();
        monitor.setPersistence(repository);

        monitor.runStarted("inv-est", "session-est", "admin", "agent_est");
        String runId = monitor.agentStarted("inv-est", "agent_est", null, "", null);

        // Before first model call: input and output estimation
        monitor.modelStarted("inv-est", "agent_est", runId);
        monitor.estimatedInput("inv-est", "agent_est", 40);
        monitor.estimatedOutput("inv-est", "agent_est", 15);

        Map<String, Object> detailBeforeReal = monitor.detail("inv-est");
        assertEquals(40L, detailBeforeReal.get("inputTokens"));
        assertEquals(15L, detailBeforeReal.get("outputTokens"));
        assertEquals(55L, detailBeforeReal.get("totalTokens"));
        assertEquals(true, detailBeforeReal.get("tokensEstimated"));

        // First provider usage arrives: 100 in, 20 out, 120 total
        monitor.usage("inv-est", "agent_est", 100, 20, 120);
        monitor.modelCompleted("inv-est", "agent_est", runId);

        // Verify estimated tokens are replaced, not added (100/20/120, NOT 140/35/175)
        Map<String, Object> detailAfterReal = monitor.detail("inv-est");
        assertEquals(100L, detailAfterReal.get("inputTokens"));
        assertEquals(20L, detailAfterReal.get("outputTokens"));
        assertEquals(120L, detailAfterReal.get("totalTokens"));
        assertEquals(false, detailAfterReal.get("tokensEstimated"));
        assertEquals(100L, monitor.summary().get("inputTokens"));
        assertEquals(20L, monitor.summary().get("outputTokens"));
        assertEquals(120L, monitor.summary().get("totalTokens"));

        // Persistence receives only the delta 100/20
        verify(repository).modelCompleted(eq("inv-est"), eq(runId), eq("agent_est"), anyLong(), anyLong(), eq(100L), eq(20L), eq("SUCCESS"), anyString());

        // Subsequent call arrives: estimatedInput is ignored when tokensEstimated is false
        monitor.modelStarted("inv-est", "agent_est", runId);
        monitor.estimatedInput("inv-est", "agent_est", 60);
        monitor.usage("inv-est", "agent_est", 50, 10, 60);
        monitor.modelCompleted("inv-est", "agent_est", runId);

        Map<String, Object> detailFinal = monitor.detail("inv-est");
        assertEquals(150L, detailFinal.get("inputTokens"));
        assertEquals(30L, detailFinal.get("outputTokens"));
        assertEquals(180L, detailFinal.get("totalTokens"));
        assertEquals(false, detailFinal.get("tokensEstimated"));
        verify(repository).modelCompleted(eq("inv-est"), eq(runId), eq("agent_est"), anyLong(), anyLong(), eq(50L), eq(10L), eq("SUCCESS"), anyString());
    }

    @Test
    void shouldNotLeakStaleDeltaIntoFailedOrSubsequentCalls() {
        IRuntimeObservationRepository repository = mock(IRuntimeObservationRepository.class);
        LightweightMonitorService monitor = new LightweightMonitorService();
        monitor.setPersistence(repository);

        monitor.runStarted("inv-fail-delta", "session-fail-delta", "admin", "agent_fail_delta");
        String runId = monitor.agentStarted("inv-fail-delta", "agent_fail_delta", null, "", null);

        // Call 1: Succeeded with 100/20/120
        monitor.modelStarted("inv-fail-delta", "agent_fail_delta", runId);
        monitor.usage("inv-fail-delta", "agent_fail_delta", 100, 20, 120);
        monitor.modelCompleted("inv-fail-delta", "agent_fail_delta", runId);
        verify(repository).modelCompleted(eq("inv-fail-delta"), eq(runId), eq("agent_fail_delta"), anyLong(), anyLong(), eq(100L), eq(20L), eq("SUCCESS"), anyString());

        // Call 2: Fails without usage metadata (e.g. timeout or auth error)
        monitor.modelStarted("inv-fail-delta", "agent_fail_delta", runId);
        monitor.modelFailed("inv-fail-delta", "agent_fail_delta", runId, new RuntimeException("503 Service Unavailable"));

        // Call 2 must persist 0/0, NOT leak call 1's 100/20
        verify(repository).modelCompleted(eq("inv-fail-delta"), eq(runId), eq("agent_fail_delta"), anyLong(), anyLong(), eq(0L), eq(0L), eq("ERROR"), argThat(msg -> msg.contains("503")));
        verify(repository, never()).modelCompleted(eq("inv-fail-delta"), eq(runId), eq("agent_fail_delta"), anyLong(), anyLong(), eq(100L), eq(20L), eq("ERROR"), anyString());

        // In-memory totals remain 100/20/120 from call 1
        assertEquals(100L, monitor.summary().get("inputTokens"));
        assertEquals(20L, monitor.summary().get("outputTokens"));
        assertEquals(120L, monitor.summary().get("totalTokens"));
    }
}
