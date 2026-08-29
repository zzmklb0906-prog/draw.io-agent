package cn.bugstack.ai.test.infrastructure;

import cn.bugstack.ai.infrastructure.persistence.JdbcRuntimeObservationRepository;
import cn.bugstack.ai.infrastructure.persistence.RuntimeInstanceIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcRuntimeObservationRepositoryTest {

    @Test
    void listRecentShouldProjectAliasesAndMapWithoutNestedJdbcCalls() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        RuntimeInstanceIdentity identity = mock(RuntimeInstanceIdentity.class);
        JdbcRuntimeObservationRepository repository = new JdbcRuntimeObservationRepository(jdbc, tx, identity);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<Map<String, Object>>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("inv-101");
        when(rs.getString("task_id")).thenReturn("task-202");
        when(rs.getString("workflowName")).thenReturn("sample_flow");
        when(rs.getString("adk_session_id")).thenReturn("session-303");
        when(rs.getString("root_agent_name")).thenReturn("root_agent");
        when(rs.getString("status")).thenReturn("SUCCESS");
        when(rs.getTimestamp("started_at")).thenReturn(new Timestamp(1000L));
        when(rs.getTimestamp("completed_at")).thenReturn(new Timestamp(2000L));
        when(rs.getLong("duration_ms")).thenReturn(1000L);
        when(rs.getLong("input_tokens")).thenReturn(50L);
        when(rs.getLong("output_tokens")).thenReturn(20L);
        when(rs.getInt("eventCount")).thenReturn(3);
        when(rs.getInt("agentCount")).thenReturn(2);
        when(rs.getInt("toolCount")).thenReturn(4);
        when(rs.getString("error_message")).thenReturn("");

        when(jdbc.query(sqlCaptor.capture(), mapperCaptor.capture(), eq("admin"), eq(10)))
                .thenAnswer(invocation -> {
                    RowMapper<Map<String, Object>> mapper = mapperCaptor.getValue();
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<Map<String, Object>> results = repository.listRecent("admin", 10);
        assertEquals(1, results.size());
        Map<String, Object> item = results.get(0);

        assertEquals("inv-101", item.get("invocationId"));
        assertEquals("task-202", item.get("taskId"));
        assertEquals("sample_flow", item.get("workflowName"));
        assertEquals(3, item.get("eventCount"));
        assertEquals(2, item.get("agentCount"));
        assertEquals(4, item.get("toolCount"));

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("\"workflowName\""));
        assertTrue(sql.contains("\"eventCount\""));
        assertTrue(sql.contains("\"agentCount\""));
        assertTrue(sql.contains("\"toolCount\""));

        // Verify that mapping issued NO additional JDBC calls
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), eq("admin"), eq(10));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void allFourBaseQueryPathsShouldProvideMappedAliases() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        RuntimeInstanceIdentity identity = mock(RuntimeInstanceIdentity.class);
        JdbcRuntimeObservationRepository repository = new JdbcRuntimeObservationRepository(jdbc, tx, identity);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        // 1. listBySession
        repository.listBySession("admin", "sess-1");
        // 2. detail
        repository.detail("admin", "inv-1");
        // 3. workflowDetail
        java.util.UUID taskId = java.util.UUID.randomUUID();
        when(jdbc.query(contains("where t.id=? and u.username=?"), any(RowMapper.class), eq(taskId), eq("admin")))
                .thenReturn(List.of(Map.of("taskId", taskId.toString(), "name", "wf", "status", "RUNNING", "createdAt", 1000L, "completedAt", 2000L, "wallDurationMs", 1000L)));
        when(jdbc.queryForObject(contains("from agent_invocation where task_id=?"), any(RowMapper.class), eq(taskId)))
                .thenReturn(Map.of("invocations", 1L, "computeDurationMs", 100L, "inputTokens", 10L, "outputTokens", 5L, "successes", 1L, "errors", 0L));
        when(jdbc.queryForObject(contains("from model_call m join agent_invocation i"), eq(Long.class), eq(taskId))).thenReturn(50L);
        when(jdbc.queryForObject(contains("from tool_execution x join agent_invocation i"), eq(Long.class), eq(taskId))).thenReturn(20L);
        when(jdbc.queryForObject(contains("workflow_state_transition where task_id=?"), eq(Long.class), eq(taskId))).thenReturn(0L);
        when(jdbc.queryForObject(contains("workflow_recovery_job"), eq(Long.class), eq(taskId))).thenReturn(0L);
        when(jdbc.queryForObject(contains("select coalesce(sum(estimated_cost),0)"), eq(java.math.BigDecimal.class), eq(taskId))).thenReturn(java.math.BigDecimal.ZERO);
        when(jdbc.queryForMap(contains("select count(*) total,count(*) filter(where x.status='SUCCESS')"), eq(taskId))).thenReturn(Map.of("total", 1, "success", 1, "retries", 0));
        when(jdbc.queryForObject(contains("select count(*) from artifact a"), eq(Long.class), eq(taskId))).thenReturn(0L);
        when(jdbc.queryForList(contains("from artifact a join agent_invocation"), eq(taskId))).thenReturn(List.of());
        when(jdbc.query(contains("from conversation_message m"), any(RowMapper.class), eq(taskId))).thenReturn(List.of());
        when(jdbc.queryForList(contains("from workflow_state_transition where task_id=?"), eq(taskId))).thenReturn(List.of());

        repository.workflowDetail("admin", taskId.toString());

        verify(jdbc, atLeastOnce()).query(sqlCaptor.capture(), any(RowMapper.class), any(), any());
        List<String> queries = sqlCaptor.getAllValues();
        for (String sql : queries) {
            if (sql.contains("agent_invocation i")) {
                assertTrue(sql.contains("\"workflowName\""), "SQL must contain workflowName alias: " + sql);
                assertTrue(sql.contains("\"eventCount\""), "SQL must contain eventCount alias: " + sql);
                assertTrue(sql.contains("\"agentCount\""), "SQL must contain agentCount alias: " + sql);
                assertTrue(sql.contains("\"toolCount\""), "SQL must contain toolCount alias: " + sql);
            }
        }
    }

    @Test
    void summaryShouldBuildSingleAggregateSqlWithTimeWindowAndRunningExemption() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        RuntimeInstanceIdentity identity = mock(RuntimeInstanceIdentity.class);
        JdbcRuntimeObservationRepository repository = new JdbcRuntimeObservationRepository(jdbc, tx, identity);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<Map<String, Object>>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("window_hours")).thenReturn(24);
        when(rs.getLong("total")).thenReturn(5L);
        when(rs.getLong("success")).thenReturn(3L);
        when(rs.getLong("errors")).thenReturn(1L);
        when(rs.getLong("active")).thenReturn(1L);
        when(rs.getDouble("success_rate")).thenReturn(0.75);
        when(rs.getLong("avg_duration")).thenReturn(150L);
        when(rs.getLong("p95_duration")).thenReturn(280L);
        when(rs.getLong("input_tokens")).thenReturn(1000L);
        when(rs.getLong("output_tokens")).thenReturn(400L);
        when(rs.getLong("total_tokens")).thenReturn(1400L);
        when(rs.getBigDecimal("estimated_cost")).thenReturn(new BigDecimal("0.004200"));

        when(jdbc.queryForObject(sqlCaptor.capture(), mapperCaptor.capture(), eq(24), eq("admin"), eq(24)))
                .thenAnswer(inv -> {
                    RowMapper<Map<String, Object>> mapper = mapperCaptor.getValue();
                    return mapper.mapRow(rs, 0);
                });

        Map<String, Object> summary = repository.summary("admin", null, 24);

        String sql = sqlCaptor.getValue();
        // 1. Single aggregate query requirements
        assertTrue(sql.contains("percentile_cont(0.95) within group"), "Must calculate p95 in database via percentile_cont: " + sql);
        assertTrue(sql.contains("filter(where i.status in ('ERROR','FAILED','INTERRUPTED'))"), "Errors must include ERROR, FAILED, INTERRUPTED: " + sql);
        assertTrue(sql.contains("/* Count currently RUNNING rows even if their start predates the window so active monitoring does not hide a long-running invocation */"), "Must document long-running active exemption in SQL: " + sql);
        assertTrue(sql.contains("(i.started_at >= now() - (? * interval '1 hour') or i.status='RUNNING')"), "Active RUNNING invocations must be exempt from start time window: " + sql);
        assertTrue(sql.contains("u.username=?"), "Must filter by user: " + sql);

        // 2. Mapped results verification
        assertEquals(24, summary.get("windowHours"));
        assertEquals(5L, summary.get("total"));
        assertEquals(3L, summary.get("success"));
        assertEquals(1L, summary.get("errors"));
        assertEquals(1L, summary.get("active"));
        assertEquals(0.75, (Double) summary.get("successRate"), 0.001);
        assertEquals(150L, summary.get("averageDurationMs"));
        assertEquals(280L, summary.get("p95DurationMs"));
        assertEquals(1000L, summary.get("inputTokens"));
        assertEquals(400L, summary.get("outputTokens"));
        assertEquals(1400L, summary.get("totalTokens"));
        assertEquals(new BigDecimal("0.004200"), summary.get("estimatedCost"));
    }

    @Test
    void summaryWithSessionIdShouldIncludeSessionFilter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        RuntimeInstanceIdentity identity = mock(RuntimeInstanceIdentity.class);
        JdbcRuntimeObservationRepository repository = new JdbcRuntimeObservationRepository(jdbc, tx, identity);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        repository.summary("admin", "session-xyz", 168);

        verify(jdbc).queryForObject(sqlCaptor.capture(), any(RowMapper.class), eq(168), eq("admin"), eq(168), eq("session-xyz"));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("and i.adk_session_id=?"), "SQL must filter by session when sessionId is provided: " + sql);
    }

    @Test
    void summarySafeZeroRateWhenNoCompletedRows() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        RuntimeInstanceIdentity identity = mock(RuntimeInstanceIdentity.class);
        JdbcRuntimeObservationRepository repository = new JdbcRuntimeObservationRepository(jdbc, tx, identity);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<Map<String, Object>>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("window_hours")).thenReturn(1);
        when(rs.getLong("total")).thenReturn(2L);
        when(rs.getLong("success")).thenReturn(0L);
        when(rs.getLong("errors")).thenReturn(0L);
        when(rs.getLong("active")).thenReturn(2L); // only RUNNING
        when(rs.getDouble("success_rate")).thenReturn(0.0);
        when(rs.getLong("avg_duration")).thenReturn(0L);
        when(rs.getLong("p95_duration")).thenReturn(0L);
        when(rs.getLong("input_tokens")).thenReturn(0L);
        when(rs.getLong("output_tokens")).thenReturn(0L);
        when(rs.getLong("total_tokens")).thenReturn(0L);
        when(rs.getBigDecimal("estimated_cost")).thenReturn(null);

        when(jdbc.queryForObject(anyString(), mapperCaptor.capture(), eq(1), eq("admin"), eq(1)))
                .thenAnswer(inv -> mapperCaptor.getValue().mapRow(rs, 0));

        Map<String, Object> summary = repository.summary("admin", null, 1);

        assertEquals(0.0, (Double) summary.get("successRate"), 0.001);
        assertEquals(0L, summary.get("success"));
        assertEquals(0L, summary.get("errors"));
        assertEquals(2L, summary.get("active"));
        assertEquals(2L, summary.get("total"));
        assertEquals(BigDecimal.ZERO, summary.get("estimatedCost"));
    }
}
