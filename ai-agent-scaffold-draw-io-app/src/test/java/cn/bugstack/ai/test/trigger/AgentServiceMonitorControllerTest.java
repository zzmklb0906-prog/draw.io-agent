package cn.bugstack.ai.test.trigger;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeObservationRepository;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.trigger.http.AgentServiceController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceMonitorControllerTest {

    @Mock
    private LightweightMonitorService lightweightMonitorService;

    @Mock
    private IRuntimeObservationRepository runtimeObservationRepository;

    @Mock
    private CapabilityRegistryService capabilityRegistryService;

    @InjectMocks
    private AgentServiceController controller;

    private static final String TEST_USER = "test-user-monitor";

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("authenticatedUserId", TEST_USER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ReflectionTestUtils.setField(controller, "lightweightMonitorService", lightweightMonitorService);
        ReflectionTestUtils.setField(controller, "runtimeObservationRepository", runtimeObservationRepository);
        ReflectionTestUtils.setField(controller, "capabilityRegistryService", capabilityRegistryService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldPreferPersistedTerminalStateOverLiveRunning() {
        Map<String, Object> liveRunning = Map.of(
                "invocationId", "inv-interrupted",
                "sessionId", "session-1",
                "status", "RUNNING",
                "startedAt", 1000L,
                "completedAt", 0L,
                "durationMs", 120L,
                "eventCount", 5L
        );
        Map<String, Object> persistedInterrupted = Map.of(
                "invocationId", "inv-interrupted",
                "sessionId", "session-1",
                "taskId", "task-recovered-1",
                "workflowName", "flow-draw",
                "status", "INTERRUPTED",
                "startedAt", 1000L,
                "completedAt", 1120L,
                "durationMs", 120L,
                "error", "Watchdog marked stale execution interrupted"
        );

        when(lightweightMonitorService.list(TEST_USER)).thenReturn(List.of(liveRunning));
        when(runtimeObservationRepository.listRecent(TEST_USER, 200)).thenReturn(List.of(persistedInterrupted));

        Response<List<Map<String, Object>>> response = controller.monitorInvocations();
        assertNotNull(response);
        assertEquals("0000", response.getCode());

        List<Map<String, Object>> data = response.getData();
        assertEquals(1, data.size());
        Map<String, Object> merged = data.get(0);

        assertEquals("inv-interrupted", merged.get("invocationId"));
        assertEquals("INTERRUPTED", merged.get("status"));
        assertEquals(1120L, merged.get("completedAt"));
        assertEquals(120L, merged.get("durationMs"));
        assertEquals("Watchdog marked stale execution interrupted", merged.get("error"));
        assertEquals("task-recovered-1", merged.get("taskId"));
        assertEquals("flow-draw", merged.get("workflowName"));
        assertEquals(5L, merged.get("eventCount")); // preserves live freshness
    }

    @Test
    void shouldPreferPersistedSuccessAndFailedOverLiveRunning() {
        Map<String, Object> live1 = Map.of(
                "invocationId", "inv-succ",
                "status", "RUNNING",
                "durationMs", 10L
        );
        Map<String, Object> persisted1 = Map.of(
                "invocationId", "inv-succ",
                "status", "SUCCESS",
                "completedAt", 5000L,
                "durationMs", 500L
        );

        Map<String, Object> live2 = Map.of(
                "invocationId", "inv-fail",
                "status", "RUNNING",
                "durationMs", 20L
        );
        Map<String, Object> persisted2 = Map.of(
                "invocationId", "inv-fail",
                "status", "FAILED",
                "completedAt", 6000L,
                "durationMs", 600L,
                "error", "Execution failed in agent"
        );

        when(lightweightMonitorService.list(TEST_USER)).thenReturn(List.of(live1, live2));
        when(runtimeObservationRepository.listRecent(TEST_USER, 200)).thenReturn(List.of(persisted1, persisted2));

        Response<List<Map<String, Object>>> response = controller.monitorInvocations();
        List<Map<String, Object>> data = response.getData();
        assertEquals(2, data.size());

        Map<String, Object> succ = data.get(0);
        assertEquals("SUCCESS", succ.get("status"));
        assertEquals(5000L, succ.get("completedAt"));
        assertEquals(500L, succ.get("durationMs"));
        assertNull(succ.get("error"));

        Map<String, Object> fail = data.get(1);
        assertEquals("FAILED", fail.get("status"));
        assertEquals(6000L, fail.get("completedAt"));
        assertEquals(600L, fail.get("durationMs"));
        assertEquals("Execution failed in agent", fail.get("error"));
    }

    @Test
    void shouldNotDowngradeLiveTerminalStateWithPersistedRunning() {
        Map<String, Object> liveSuccess = Map.of(
                "invocationId", "inv-live-done",
                "status", "SUCCESS",
                "startedAt", 2000L,
                "completedAt", 2500L,
                "durationMs", 500L
        );
        Map<String, Object> persistedStaleRunning = Map.of(
                "invocationId", "inv-live-done",
                "status", "RUNNING",
                "startedAt", 2000L,
                "completedAt", 0L,
                "durationMs", 100L
        );

        Map<String, Object> liveError = Map.of(
                "invocationId", "inv-live-err",
                "status", "ERROR",
                "startedAt", 3000L,
                "completedAt", 3400L,
                "durationMs", 400L,
                "error", "Model rate limit"
        );
        Map<String, Object> persistedStaleRunning2 = Map.of(
                "invocationId", "inv-live-err",
                "status", "RUNNING",
                "startedAt", 3000L,
                "completedAt", 0L,
                "durationMs", 150L
        );

        when(lightweightMonitorService.list(TEST_USER)).thenReturn(List.of(liveSuccess, liveError));
        when(runtimeObservationRepository.listRecent(TEST_USER, 200)).thenReturn(List.of(persistedStaleRunning, persistedStaleRunning2));

        Response<List<Map<String, Object>>> response = controller.monitorInvocations();
        List<Map<String, Object>> data = response.getData();
        assertEquals(2, data.size());

        Map<String, Object> res1 = data.get(0);
        assertEquals("SUCCESS", res1.get("status"));
        assertEquals(2500L, res1.get("completedAt"));
        assertEquals(500L, res1.get("durationMs"));

        Map<String, Object> res2 = data.get(1);
        assertEquals("ERROR", res2.get("status"));
        assertEquals(3400L, res2.get("completedAt"));
        assertEquals(400L, res2.get("durationMs"));
        assertEquals("Model rate limit", res2.get("error"));
    }

    @Test
    void shouldEnrichMissingAndBlankWorkflowMetadataFromPersistedData() {
        Map<String, Object> liveBlankWorkflow = Map.of(
                "invocationId", "inv-enrich-1",
                "status", "RUNNING",
                "workflowName", ""
        );
        Map<String, Object> persistedMetadata1 = Map.of(
                "invocationId", "inv-enrich-1",
                "status", "RUNNING",
                "taskId", "task-uuid-1",
                "workflowName", "EnrichedWorkflowTitle"
        );

        Map<String, Object> liveUnknownWorkflow = Map.of(
                "invocationId", "inv-enrich-2",
                "status", "RUNNING",
                "workflowName", "unknown"
        );
        Map<String, Object> persistedMetadata2 = Map.of(
                "invocationId", "inv-enrich-2",
                "status", "RUNNING",
                "taskId", "task-uuid-2",
                "workflowName", "ActualWorkflowTitle"
        );

        Map<String, Object> liveExplicitWorkflow = Map.of(
                "invocationId", "inv-enrich-3",
                "status", "RUNNING",
                "workflowName", "ExplicitWorkflowName"
        );
        Map<String, Object> persistedMetadata3 = Map.of(
                "invocationId", "inv-enrich-3",
                "status", "RUNNING",
                "taskId", "task-uuid-3",
                "workflowName", "OtherWorkflowTitle"
        );

        when(lightweightMonitorService.list(TEST_USER)).thenReturn(List.of(liveBlankWorkflow, liveUnknownWorkflow, liveExplicitWorkflow));
        when(runtimeObservationRepository.listRecent(TEST_USER, 200)).thenReturn(List.of(persistedMetadata1, persistedMetadata2, persistedMetadata3));

        Response<List<Map<String, Object>>> response = controller.monitorInvocations();
        List<Map<String, Object>> data = response.getData();
        assertEquals(3, data.size());

        assertEquals("task-uuid-1", data.get(0).get("taskId"));
        assertEquals("EnrichedWorkflowTitle", data.get(0).get("workflowName"));

        assertEquals("task-uuid-2", data.get(1).get("taskId"));
        assertEquals("ActualWorkflowTitle", data.get(1).get("workflowName"));

        assertEquals("task-uuid-3", data.get(2).get("taskId"));
        assertEquals("ExplicitWorkflowName", data.get(2).get("workflowName"));
    }

    @Test
    void shouldPreserveLiveOnlyInvocationsAndMaintainStableOrder() {
        Map<String, Object> liveOnly1 = Map.of("invocationId", "inv-live-1", "status", "RUNNING");
        Map<String, Object> liveShared = Map.of("invocationId", "inv-shared", "status", "RUNNING");
        Map<String, Object> liveOnly2 = Map.of("invocationId", "inv-live-2", "status", "RUNNING");

        Map<String, Object> persistedShared = Map.of("invocationId", "inv-shared", "status", "RUNNING", "taskId", "task-s");
        Map<String, Object> persistedOnly1 = Map.of("invocationId", "inv-persisted-1", "status", "SUCCESS", "taskId", "task-p1");
        Map<String, Object> persistedOnly2 = Map.of("invocationId", "inv-persisted-2", "status", "FAILED", "taskId", "task-p2");

        when(lightweightMonitorService.list(TEST_USER)).thenReturn(List.of(liveOnly1, liveShared, liveOnly2));
        when(runtimeObservationRepository.listRecent(TEST_USER, 200)).thenReturn(List.of(persistedShared, persistedOnly1, persistedOnly2));

        Response<List<Map<String, Object>>> response = controller.monitorInvocations();
        List<Map<String, Object>> data = response.getData();

        assertEquals(5, data.size());
        assertEquals("inv-live-1", data.get(0).get("invocationId"));
        assertEquals("inv-shared", data.get(1).get("invocationId"));
        assertEquals("task-s", data.get(1).get("taskId"));
        assertEquals("inv-live-2", data.get(2).get("invocationId"));
        assertEquals("inv-persisted-1", data.get(3).get("invocationId"));
        assertEquals("inv-persisted-2", data.get(4).get("invocationId"));
    }

    @Test
    void shouldSafelyMergeImmutableMapsWithoutThrowing() {
        Map<String, Object> liveImmutable = Map.of(
                "invocationId", "inv-immutable",
                "status", "RUNNING",
                "workflowName", ""
        );
        Map<String, Object> persistedImmutable = Map.of(
                "invocationId", "inv-immutable",
                "status", "SUCCESS",
                "taskId", "task-imm-1",
                "workflowName", "ImmutableWorkflow",
                "completedAt", 9999L,
                "durationMs", 888L
        );

        when(lightweightMonitorService.list(TEST_USER)).thenReturn(List.of(liveImmutable));
        when(runtimeObservationRepository.listRecent(TEST_USER, 200)).thenReturn(List.of(persistedImmutable));

        assertDoesNotThrow(() -> {
            Response<List<Map<String, Object>>> response = controller.monitorInvocations();
            assertEquals(1, response.getData().size());
            Map<String, Object> item = response.getData().get(0);
            assertEquals("SUCCESS", item.get("status"));
            assertEquals("task-imm-1", item.get("taskId"));
            assertEquals("ImmutableWorkflow", item.get("workflowName"));
            assertEquals(9999L, item.get("completedAt"));
            assertEquals(888L, item.get("durationMs"));
        });
    }

    @Test
    void shouldDefaultTo24HoursWhenHoursParamMissingOrInvalid() {
        when(lightweightMonitorService.summary(TEST_USER)).thenReturn(Map.of("registeredTools", List.of("tool-a")));
        when(capabilityRegistryService.size()).thenReturn(3);
        when(runtimeObservationRepository.summary(TEST_USER, null, 24)).thenReturn(Map.of("total", 1L));

        // 1. Missing hours (null) defaults to 24
        Response<Map<String, Object>> res1 = controller.monitorSummary(null, null);
        assertEquals("0000", res1.getCode());
        verify(runtimeObservationRepository).summary(TEST_USER, null, 24);

        // 2. Invalid hours (e.g. 5, 0, -10, 999) fallback to 24
        controller.monitorSummary(null, 5);
        controller.monitorSummary(null, 0);
        controller.monitorSummary(null, -10);
        controller.monitorSummary(null, 999);
        verify(runtimeObservationRepository, org.mockito.Mockito.times(5)).summary(TEST_USER, null, 24);
    }

    @Test
    void shouldPassValidWindowHoursAndEnforceSessionFilter() {
        when(lightweightMonitorService.summary(TEST_USER)).thenReturn(Map.of("registeredTools", List.of()));
        when(capabilityRegistryService.size()).thenReturn(0);
        when(runtimeObservationRepository.summary(TEST_USER, "sess-1", 1)).thenReturn(Map.of("windowHours", 1));
        when(runtimeObservationRepository.summary(TEST_USER, "sess-1", 168)).thenReturn(Map.of("windowHours", 168));

        Response<Map<String, Object>> res1h = controller.monitorSummary("sess-1", 1);
        assertEquals("0000", res1h.getCode());
        assertEquals(1, res1h.getData().get("windowHours"));
        verify(runtimeObservationRepository).summary(TEST_USER, "sess-1", 1);

        Response<Map<String, Object>> res7d = controller.monitorSummary("sess-1", 168);
        assertEquals("0000", res7d.getCode());
        assertEquals(168, res7d.getData().get("windowHours"));
        verify(runtimeObservationRepository).summary(TEST_USER, "sess-1", 168);
    }

    @Test
    void shouldEnrichPersistedSummaryWithLiveToolsAndCapabilities() {
        Map<String, Object> persisted = new java.util.LinkedHashMap<>();
        persisted.put("windowHours", 24);
        persisted.put("total", 10L);
        persisted.put("success", 8L);
        persisted.put("errors", 1L);
        persisted.put("active", 1L);
        persisted.put("successRate", 0.8889);
        persisted.put("averageDurationMs", 200L);
        persisted.put("p95DurationMs", 450L);
        persisted.put("inputTokens", 5000L);
        persisted.put("outputTokens", 1200L);
        persisted.put("totalTokens", 6200L);
        persisted.put("estimatedCost", new java.math.BigDecimal("0.015000"));
        when(runtimeObservationRepository.summary(TEST_USER, null, 24)).thenReturn(persisted);
        when(lightweightMonitorService.summary(TEST_USER)).thenReturn(Map.of("registeredTools", List.of("drawio", "search")));
        when(capabilityRegistryService.size()).thenReturn(7);

        Response<Map<String, Object>> response = controller.monitorSummary(null, 24);
        assertNotNull(response);
        assertEquals("0000", response.getCode());

        Map<String, Object> data = response.getData();
        assertEquals(24, data.get("windowHours"));
        assertEquals(10L, data.get("total"));
        assertEquals(8L, data.get("success"));
        assertEquals(1L, data.get("errors"));
        assertEquals(1L, data.get("active"));
        assertEquals(0.8889, (Double) data.get("successRate"), 0.0001);
        assertEquals(200L, data.get("averageDurationMs"));
        assertEquals(450L, data.get("p95DurationMs"));
        assertEquals(5000L, data.get("inputTokens"));
        assertEquals(1200L, data.get("outputTokens"));
        assertEquals(6200L, data.get("totalTokens"));
        assertEquals(new java.math.BigDecimal("0.015000"), data.get("estimatedCost"));
        assertEquals(List.of("drawio", "search"), data.get("registeredTools"));
        assertEquals(7, data.get("registeredCapabilities"));
    }
}
