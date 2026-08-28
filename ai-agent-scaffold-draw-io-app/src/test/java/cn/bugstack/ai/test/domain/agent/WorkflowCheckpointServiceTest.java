package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkflowCheckpointRepository;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowCheckpointEntity;
import cn.bugstack.ai.domain.agent.service.workflow.WorkflowCheckpointService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowCheckpointServiceTest {

    private WorkflowCheckpointService service;
    private InMemoryWorkflowCheckpointRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkflowCheckpointService();
        repository = new InMemoryWorkflowCheckpointRepository();
        Field repoField = WorkflowCheckpointService.class.getDeclaredField("repository");
        repoField.setAccessible(true);
        repoField.set(service, repository);
    }

    @Test
    void shouldPerformAllFourLegalUserTransitionCategories() {
        // Category 1: WAITING_APPROVAL + APPROVE -> RUNNING/DRAWING
        WorkflowCheckpointEntity cp1 = service.start("300000", "user-1", "session-1", "draw flow");
        service.approval(cp1.getCheckpointId(), "inv-1", "{\"rewrittenPrompt\":\"draw user login flow\"}");
        WorkflowCheckpointEntity resumed1 = service.resume(cp1.getCheckpointId(), 2, "APPROVE");
        assertEquals(WorkflowCheckpointService.RUNNING, resumed1.getStatus());
        assertEquals("DRAWING", resumed1.getStage());
        assertEquals(3, resumed1.getRevision());
        WorkflowCheckpointEntity stored1 = service.get(cp1.getCheckpointId());
        assertEquals(WorkflowCheckpointService.RUNNING, stored1.getStatus());
        assertEquals("DRAWING", stored1.getStage());
        assertEquals(3, stored1.getRevision());

        // Category 2: WAITING_APPROVAL + REVISE -> RUNNING/ANALYSIS
        WorkflowCheckpointEntity cp2 = service.start("300000", "user-1", "session-2", "draw flow");
        service.approval(cp2.getCheckpointId(), "inv-2", "{\"rewrittenPrompt\":\"initial draft\"}");
        WorkflowCheckpointEntity resumed2 = service.resume(cp2.getCheckpointId(), 2, "REVISE");
        assertEquals(WorkflowCheckpointService.RUNNING, resumed2.getStatus());
        assertEquals("ANALYSIS", resumed2.getStage());
        assertEquals(3, resumed2.getRevision());
        WorkflowCheckpointEntity stored2 = service.get(cp2.getCheckpointId());
        assertEquals(WorkflowCheckpointService.RUNNING, stored2.getStatus());
        assertEquals("ANALYSIS", stored2.getStage());
        assertEquals(3, stored2.getRevision());

        // Category 3a: WAITING_TOOL_APPROVAL + TOOL_APPROVE -> RUNNING/TOOL_EXECUTION
        WorkflowCheckpointEntity cp3a = service.start("300000", "user-1", "session-3a", "draw flow");
        service.waitForToolApproval(cp3a.getCheckpointId(), "inv-3a", "call-tool-1", "{\"action\":\"drop_table\"}");
        WorkflowCheckpointEntity resumed3a = service.resume(cp3a.getCheckpointId(), 2, "TOOL_APPROVE");
        assertEquals(WorkflowCheckpointService.RUNNING, resumed3a.getStatus());
        assertEquals("TOOL_EXECUTION", resumed3a.getStage());
        assertEquals(3, resumed3a.getRevision());
        assertEquals("call-tool-1", resumed3a.getPendingToolCallId());
        assertEquals("{\"action\":\"drop_table\"}", resumed3a.getPendingToolConfirmationJson());

        // Category 3b: WAITING_TOOL_APPROVAL + TOOL_DENY -> RUNNING/TOOL_EXECUTION
        WorkflowCheckpointEntity cp3b = service.start("300000", "user-1", "session-3b", "draw flow");
        service.waitForToolApproval(cp3b.getCheckpointId(), "inv-3b", "call-tool-2", "{\"action\":\"format_disk\"}");
        WorkflowCheckpointEntity resumed3b = service.resume(cp3b.getCheckpointId(), 2, "TOOL_DENY");
        assertEquals(WorkflowCheckpointService.RUNNING, resumed3b.getStatus());
        assertEquals("TOOL_EXECUTION", resumed3b.getStage());
        assertEquals(3, resumed3b.getRevision());
        assertEquals("call-tool-2", resumed3b.getPendingToolCallId());
        assertEquals("{\"action\":\"format_disk\"}", resumed3b.getPendingToolConfirmationJson());

        // Category 4: PAUSED + CONTINUE -> RUNNING (stage preserved)
        WorkflowCheckpointEntity cp4 = service.start("300000", "user-1", "session-4", "draw flow");
        assertEquals("ANALYSIS", cp4.getStage());
        service.pause(cp4.getCheckpointId());
        WorkflowCheckpointEntity paused = service.get(cp4.getCheckpointId());
        assertEquals(WorkflowCheckpointService.PAUSED, paused.getStatus());
        assertEquals("ANALYSIS", paused.getStage());
        assertEquals(2, paused.getRevision());

        WorkflowCheckpointEntity resumed4 = service.resume(cp4.getCheckpointId(), 2, "CONTINUE");
        assertEquals(WorkflowCheckpointService.RUNNING, resumed4.getStatus());
        assertEquals("ANALYSIS", resumed4.getStage());
        assertEquals(3, resumed4.getRevision());

        // Also test CONTINUE preserving DRAWING stage
        WorkflowCheckpointEntity cp4b = service.start("300000", "user-1", "session-4b", "draw flow");
        service.approval(cp4b.getCheckpointId(), "inv-4b", "{\"rewrittenPrompt\":\"draw system\"}");
        service.resume(cp4b.getCheckpointId(), 2, "APPROVE");
        service.pause(cp4b.getCheckpointId());
        WorkflowCheckpointEntity paused4b = service.get(cp4b.getCheckpointId());
        assertEquals("DRAWING", paused4b.getStage());
        assertEquals(4, paused4b.getRevision());

        WorkflowCheckpointEntity resumed4b = service.resume(cp4b.getCheckpointId(), 4, "CONTINUE");
        assertEquals(WorkflowCheckpointService.RUNNING, resumed4b.getStatus());
        assertEquals("DRAWING", resumed4b.getStage());
        assertEquals(5, resumed4b.getRevision());
    }

    @Test
    void shouldRejectMismatchedStateAndDecisionWithoutMutatingStateOrRevision() {
        // 1. WAITING_APPROVAL state rejected for CONTINUE, TOOL_APPROVE, TOOL_DENY
        WorkflowCheckpointEntity cp1 = service.start("300000", "user-1", "session-1", "draw flow");
        service.approval(cp1.getCheckpointId(), "inv-1", "{\"rewrittenPrompt\":\"plan\"}");
        long revAfterApproval = service.get(cp1.getCheckpointId()).getRevision();

        assertThrows(AppException.class, () -> service.resume(cp1.getCheckpointId(), revAfterApproval, "CONTINUE"));
        assertCheckpointUnchanged(cp1.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", revAfterApproval);

        assertThrows(AppException.class, () -> service.resume(cp1.getCheckpointId(), revAfterApproval, "TOOL_APPROVE"));
        assertCheckpointUnchanged(cp1.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", revAfterApproval);

        assertThrows(AppException.class, () -> service.resume(cp1.getCheckpointId(), revAfterApproval, "TOOL_DENY"));
        assertCheckpointUnchanged(cp1.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", revAfterApproval);

        // 2. WAITING_TOOL_APPROVAL state rejected for APPROVE, REVISE, CONTINUE
        WorkflowCheckpointEntity cp2 = service.start("300000", "user-1", "session-2", "draw flow");
        service.waitForToolApproval(cp2.getCheckpointId(), "inv-2", "call-1", "{}");
        long revAfterToolWait = service.get(cp2.getCheckpointId()).getRevision();

        assertThrows(AppException.class, () -> service.resume(cp2.getCheckpointId(), revAfterToolWait, "APPROVE"));
        assertCheckpointUnchanged(cp2.getCheckpointId(), WorkflowCheckpointService.WAITING_TOOL_APPROVAL, "TOOL_APPROVAL", revAfterToolWait);

        assertThrows(AppException.class, () -> service.resume(cp2.getCheckpointId(), revAfterToolWait, "REVISE"));
        assertCheckpointUnchanged(cp2.getCheckpointId(), WorkflowCheckpointService.WAITING_TOOL_APPROVAL, "TOOL_APPROVAL", revAfterToolWait);

        assertThrows(AppException.class, () -> service.resume(cp2.getCheckpointId(), revAfterToolWait, "CONTINUE"));
        assertCheckpointUnchanged(cp2.getCheckpointId(), WorkflowCheckpointService.WAITING_TOOL_APPROVAL, "TOOL_APPROVAL", revAfterToolWait);

        // 3. PAUSED state rejected for APPROVE, REVISE, TOOL_APPROVE, TOOL_DENY
        WorkflowCheckpointEntity cp3 = service.start("300000", "user-1", "session-3", "draw flow");
        service.pause(cp3.getCheckpointId());
        long revAfterPause = service.get(cp3.getCheckpointId()).getRevision();

        assertThrows(AppException.class, () -> service.resume(cp3.getCheckpointId(), revAfterPause, "APPROVE"));
        assertCheckpointUnchanged(cp3.getCheckpointId(), WorkflowCheckpointService.PAUSED, "ANALYSIS", revAfterPause);

        assertThrows(AppException.class, () -> service.resume(cp3.getCheckpointId(), revAfterPause, "REVISE"));
        assertCheckpointUnchanged(cp3.getCheckpointId(), WorkflowCheckpointService.PAUSED, "ANALYSIS", revAfterPause);

        assertThrows(AppException.class, () -> service.resume(cp3.getCheckpointId(), revAfterPause, "TOOL_APPROVE"));
        assertCheckpointUnchanged(cp3.getCheckpointId(), WorkflowCheckpointService.PAUSED, "ANALYSIS", revAfterPause);

        assertThrows(AppException.class, () -> service.resume(cp3.getCheckpointId(), revAfterPause, "TOOL_DENY"));
        assertCheckpointUnchanged(cp3.getCheckpointId(), WorkflowCheckpointService.PAUSED, "ANALYSIS", revAfterPause);

        // 4. RUNNING state rejected for all user resume decisions
        WorkflowCheckpointEntity cp4 = service.start("300000", "user-1", "session-4", "draw flow");
        long revRunning = cp4.getRevision();

        assertThrows(AppException.class, () -> service.resume(cp4.getCheckpointId(), revRunning, "APPROVE"));
        assertCheckpointUnchanged(cp4.getCheckpointId(), WorkflowCheckpointService.RUNNING, "ANALYSIS", revRunning);

        assertThrows(AppException.class, () -> service.resume(cp4.getCheckpointId(), revRunning, "REVISE"));
        assertCheckpointUnchanged(cp4.getCheckpointId(), WorkflowCheckpointService.RUNNING, "ANALYSIS", revRunning);

        assertThrows(AppException.class, () -> service.resume(cp4.getCheckpointId(), revRunning, "TOOL_APPROVE"));
        assertCheckpointUnchanged(cp4.getCheckpointId(), WorkflowCheckpointService.RUNNING, "ANALYSIS", revRunning);

        assertThrows(AppException.class, () -> service.resume(cp4.getCheckpointId(), revRunning, "TOOL_DENY"));
        assertCheckpointUnchanged(cp4.getCheckpointId(), WorkflowCheckpointService.RUNNING, "ANALYSIS", revRunning);

        assertThrows(AppException.class, () -> service.resume(cp4.getCheckpointId(), revRunning, "CONTINUE"));
        assertCheckpointUnchanged(cp4.getCheckpointId(), WorkflowCheckpointService.RUNNING, "ANALYSIS", revRunning);
    }

    @Test
    void shouldRejectTerminalAndFailedStatesOnUserResume() {
        // 1. COMPLETED state
        WorkflowCheckpointEntity cp1 = service.start("300000", "user-1", "session-1", "draw flow");
        service.finish(cp1.getCheckpointId(), true, null);
        long revCompleted = service.get(cp1.getCheckpointId()).getRevision();

        for (String decision : new String[]{"APPROVE", "REVISE", "TOOL_APPROVE", "TOOL_DENY", "CONTINUE"}) {
            AppException ex = assertThrows(AppException.class, () -> service.resume(cp1.getCheckpointId(), revCompleted, decision));
            assertEquals("CHECKPOINT_TERMINAL", ex.getCode());
            assertCheckpointUnchanged(cp1.getCheckpointId(), WorkflowCheckpointService.COMPLETED, "TERMINAL", revCompleted);
        }

        // 2. CANCELLED state
        WorkflowCheckpointEntity cp2 = service.start("300000", "user-1", "session-2", "draw flow");
        service.cancel(cp2.getCheckpointId());
        long revCancelled = service.get(cp2.getCheckpointId()).getRevision();

        for (String decision : new String[]{"APPROVE", "REVISE", "TOOL_APPROVE", "TOOL_DENY", "CONTINUE"}) {
            AppException ex = assertThrows(AppException.class, () -> service.resume(cp2.getCheckpointId(), revCancelled, decision));
            assertEquals("CHECKPOINT_TERMINAL", ex.getCode());
            assertCheckpointUnchanged(cp2.getCheckpointId(), WorkflowCheckpointService.CANCELLED, "TERMINAL", revCancelled);
        }

        // 3. FAILED state
        WorkflowCheckpointEntity cp3 = service.start("300000", "user-1", "session-3", "draw flow");
        service.finish(cp3.getCheckpointId(), false, "LLM timeout");
        long revFailed = service.get(cp3.getCheckpointId()).getRevision();

        for (String decision : new String[]{"APPROVE", "REVISE", "TOOL_APPROVE", "TOOL_DENY", "CONTINUE"}) {
            AppException ex = assertThrows(AppException.class, () -> service.resume(cp3.getCheckpointId(), revFailed, decision));
            assertEquals("CHECKPOINT_STATE_MISMATCH", ex.getCode());
            assertCheckpointUnchanged(cp3.getCheckpointId(), WorkflowCheckpointService.FAILED, "TERMINAL", revFailed);
        }
    }

    @Test
    void shouldRejectUnsupportedAndNullDecisionsWithoutMutating() {
        WorkflowCheckpointEntity cp = service.start("300000", "user-1", "session-1", "draw flow");
        service.approval(cp.getCheckpointId(), "inv-1", "{\"rewrittenPrompt\":\"plan\"}");
        long rev = service.get(cp.getCheckpointId()).getRevision();

        // null decision
        AppException exNull = assertThrows(AppException.class, () -> service.resume(cp.getCheckpointId(), rev, null));
        assertEquals("CHECKPOINT_DECISION_INVALID", exNull.getCode());
        assertCheckpointUnchanged(cp.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev);

        // empty decision
        AppException exEmpty = assertThrows(AppException.class, () -> service.resume(cp.getCheckpointId(), rev, ""));
        assertEquals("CHECKPOINT_DECISION_INVALID", exEmpty.getCode());
        assertCheckpointUnchanged(cp.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev);

        // blank decision
        AppException exBlank = assertThrows(AppException.class, () -> service.resume(cp.getCheckpointId(), rev, "   "));
        assertEquals("CHECKPOINT_DECISION_INVALID", exBlank.getCode());
        assertCheckpointUnchanged(cp.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev);

        // unsupported decision strings
        for (String invalidDecision : new String[]{"RESTART", "ROLLBACK", "ABORT", "FORCE_RUN", "unknown"}) {
            AppException ex = assertThrows(AppException.class, () -> service.resume(cp.getCheckpointId(), rev, invalidDecision));
            assertEquals("CHECKPOINT_DECISION_INVALID", ex.getCode());
            assertCheckpointUnchanged(cp.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev);
        }
    }

    @Test
    void shouldRejectStaleRevisionWithoutMutating() {
        WorkflowCheckpointEntity cp = service.start("300000", "user-1", "session-1", "draw flow");
        service.approval(cp.getCheckpointId(), "inv-1", "{\"rewrittenPrompt\":\"valid plan\"}");
        long currentRev = service.get(cp.getCheckpointId()).getRevision(); // 2

        // Stale revision: expected 1, but current is 2
        AppException ex1 = assertThrows(AppException.class, () -> service.resume(cp.getCheckpointId(), 1, "APPROVE"));
        assertEquals("CHECKPOINT_CONFLICT", ex1.getCode());
        assertCheckpointUnchanged(cp.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", currentRev);

        // Future / mismatched revision: expected 99
        AppException ex2 = assertThrows(AppException.class, () -> service.resume(cp.getCheckpointId(), 99, "APPROVE"));
        assertEquals("CHECKPOINT_CONFLICT", ex2.getCode());
        assertCheckpointUnchanged(cp.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", currentRev);
    }

    @Test
    void shouldRejectMissingMalformedOrBlankApprovalBriefOnApproveWithoutMutating() {
        // Case 1: approvalJson is null
        WorkflowCheckpointEntity cp1 = service.start("300000", "user-1", "session-1", "draw flow");
        service.approval(cp1.getCheckpointId(), "inv-1", null);
        long rev1 = service.get(cp1.getCheckpointId()).getRevision();

        AppException ex1 = assertThrows(AppException.class, () -> service.resume(cp1.getCheckpointId(), rev1, "APPROVE"));
        assertEquals("CHECKPOINT_APPROVAL_MISSING", ex1.getCode());
        assertCheckpointUnchanged(cp1.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev1);

        // Case 2: approvalJson is empty string
        WorkflowCheckpointEntity cp2 = service.start("300000", "user-1", "session-2", "draw flow");
        service.approval(cp2.getCheckpointId(), "inv-2", "");
        long rev2 = service.get(cp2.getCheckpointId()).getRevision();

        AppException ex2 = assertThrows(AppException.class, () -> service.resume(cp2.getCheckpointId(), rev2, "APPROVE"));
        assertEquals("CHECKPOINT_APPROVAL_MISSING", ex2.getCode());
        assertCheckpointUnchanged(cp2.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev2);

        // Case 3: approvalJson is whitespace only
        WorkflowCheckpointEntity cp3 = service.start("300000", "user-1", "session-3", "draw flow");
        service.approval(cp3.getCheckpointId(), "inv-3", "   ");
        long rev3 = service.get(cp3.getCheckpointId()).getRevision();

        AppException ex3 = assertThrows(AppException.class, () -> service.resume(cp3.getCheckpointId(), rev3, "APPROVE"));
        assertEquals("CHECKPOINT_APPROVAL_MISSING", ex3.getCode());
        assertCheckpointUnchanged(cp3.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev3);

        // Case 4: approvalJson is malformed JSON
        WorkflowCheckpointEntity cp4 = service.start("300000", "user-1", "session-4", "draw flow");
        service.approval(cp4.getCheckpointId(), "inv-4", "{not a valid json");
        long rev4 = service.get(cp4.getCheckpointId()).getRevision();

        AppException ex4 = assertThrows(AppException.class, () -> service.resume(cp4.getCheckpointId(), rev4, "APPROVE"));
        assertEquals("CHECKPOINT_APPROVAL_MISSING", ex4.getCode());
        assertCheckpointUnchanged(cp4.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev4);

        // Case 5: approvalJson has no rewrittenPrompt field
        WorkflowCheckpointEntity cp5 = service.start("300000", "user-1", "session-5", "draw flow");
        service.approval(cp5.getCheckpointId(), "inv-5", "{\"summary\":\"analysis summary only\"}");
        long rev5 = service.get(cp5.getCheckpointId()).getRevision();

        AppException ex5 = assertThrows(AppException.class, () -> service.resume(cp5.getCheckpointId(), rev5, "APPROVE"));
        assertEquals("CHECKPOINT_APPROVAL_MISSING", ex5.getCode());
        assertCheckpointUnchanged(cp5.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev5);

        // Case 6: approvalJson has blank rewrittenPrompt
        WorkflowCheckpointEntity cp6 = service.start("300000", "user-1", "session-6", "draw flow");
        service.approval(cp6.getCheckpointId(), "inv-6", "{\"rewrittenPrompt\":\"   \"}");
        long rev6 = service.get(cp6.getCheckpointId()).getRevision();

        AppException ex6 = assertThrows(AppException.class, () -> service.resume(cp6.getCheckpointId(), rev6, "APPROVE"));
        assertEquals("CHECKPOINT_APPROVAL_MISSING", ex6.getCode());
        assertCheckpointUnchanged(cp6.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", rev6);
    }

    @Test
    void shouldRetainToolConfirmationFieldsAfterApproveAndDenyResumesAndFinish() {
        String callId1 = "call-danger-1";
        String confirmJson1 = "{\"command\":\"deleteDatabase\",\"cluster\":\"prod\"}";

        WorkflowCheckpointEntity cp1 = service.start("300000", "user-1", "session-tool-1", "ops task");
        service.waitForToolApproval(cp1.getCheckpointId(), "inv-tool-1", callId1, confirmJson1);

        WorkflowCheckpointEntity waiting1 = service.get(cp1.getCheckpointId());
        assertEquals(WorkflowCheckpointService.WAITING_TOOL_APPROVAL, waiting1.getStatus());
        assertEquals("TOOL_APPROVAL", waiting1.getStage());
        assertEquals(callId1, waiting1.getPendingToolCallId());
        assertEquals(confirmJson1, waiting1.getPendingToolConfirmationJson());

        // Resume with TOOL_APPROVE
        WorkflowCheckpointEntity resumed1 = service.resume(cp1.getCheckpointId(), waiting1.getRevision(), "TOOL_APPROVE");
        assertEquals(WorkflowCheckpointService.RUNNING, resumed1.getStatus());
        assertEquals("TOOL_EXECUTION", resumed1.getStage());
        assertEquals(callId1, resumed1.getPendingToolCallId());
        assertEquals(confirmJson1, resumed1.getPendingToolConfirmationJson());

        WorkflowCheckpointEntity stored1 = service.get(cp1.getCheckpointId());
        assertEquals(callId1, stored1.getPendingToolCallId());
        assertEquals(confirmJson1, stored1.getPendingToolConfirmationJson());

        // Finish successfully
        service.finish(cp1.getCheckpointId(), true, null);
        WorkflowCheckpointEntity finished1 = service.get(cp1.getCheckpointId());
        assertEquals(WorkflowCheckpointService.COMPLETED, finished1.getStatus());
        assertEquals(callId1, finished1.getPendingToolCallId());
        assertEquals(confirmJson1, finished1.getPendingToolConfirmationJson());

        // Test with TOOL_DENY and finish failed
        String callId2 = "call-danger-2";
        String confirmJson2 = "{\"command\":\"truncateTable\",\"target\":\"orders\"}";

        WorkflowCheckpointEntity cp2 = service.start("300000", "user-1", "session-tool-2", "ops task 2");
        service.waitForToolApproval(cp2.getCheckpointId(), "inv-tool-2", callId2, confirmJson2);

        WorkflowCheckpointEntity resumed2 = service.resume(cp2.getCheckpointId(), 2, "TOOL_DENY");
        assertEquals(WorkflowCheckpointService.RUNNING, resumed2.getStatus());
        assertEquals("TOOL_EXECUTION", resumed2.getStage());
        assertEquals(callId2, resumed2.getPendingToolCallId());
        assertEquals(confirmJson2, resumed2.getPendingToolConfirmationJson());

        service.finish(cp2.getCheckpointId(), false, "User denied tool execution");
        WorkflowCheckpointEntity finished2 = service.get(cp2.getCheckpointId());
        assertEquals(WorkflowCheckpointService.FAILED, finished2.getStatus());
        assertEquals(callId2, finished2.getPendingToolCallId());
        assertEquals(confirmJson2, finished2.getPendingToolConfirmationJson());
        assertEquals("User denied tool execution", finished2.getErrorMessage());
    }

    @Test
    void shouldHandleRecoveryResumePreservingStageAndRejectingNonRunningOrNonReplayableStates() {
        // 1. Initial recovery on PAUSED + ANALYSIS stage -> succeeds, sets RUNNING, preserves stage
        WorkflowCheckpointEntity cp1 = service.start("300000", "user-1", "session-rec-1", "draw flow");
        service.pause(cp1.getCheckpointId()); // status PAUSED, stage ANALYSIS, revision 2
        WorkflowCheckpointEntity paused1 = service.get(cp1.getCheckpointId());
        assertEquals(WorkflowCheckpointService.PAUSED, paused1.getStatus());
        assertEquals("ANALYSIS", paused1.getStage());
        assertEquals(2, paused1.getRevision());

        WorkflowCheckpointEntity recovered1 = service.resumeRecovery(cp1.getCheckpointId(), 2);
        assertEquals(WorkflowCheckpointService.RUNNING, recovered1.getStatus());
        assertEquals("ANALYSIS", recovered1.getStage());
        assertEquals(3, recovered1.getRevision());
        assertCheckpointUnchanged(cp1.getCheckpointId(), WorkflowCheckpointService.RUNNING, "ANALYSIS", 3);

        // 2. Second RUNNING retry with the newly returned revision -> succeeds, remains RUNNING, preserves stage
        WorkflowCheckpointEntity retried1 = service.resumeRecovery(cp1.getCheckpointId(), 3);
        assertEquals(WorkflowCheckpointService.RUNNING, retried1.getStatus());
        assertEquals("ANALYSIS", retried1.getStage());
        assertEquals(4, retried1.getRevision());
        assertCheckpointUnchanged(cp1.getCheckpointId(), WorkflowCheckpointService.RUNNING, "ANALYSIS", 4);

        // 3. Initial recovery on PAUSED + DRAWING stage and subsequent RUNNING retry
        WorkflowCheckpointEntity cp2 = service.start("300000", "user-1", "session-rec-2", "draw flow");
        service.approval(cp2.getCheckpointId(), "inv-2", "{\"rewrittenPrompt\":\"draw architecture\"}");
        service.resume(cp2.getCheckpointId(), 2, "APPROVE"); // status RUNNING, stage DRAWING, revision 3
        service.pause(cp2.getCheckpointId()); // status PAUSED, stage DRAWING, revision 4
        WorkflowCheckpointEntity paused2 = service.get(cp2.getCheckpointId());
        assertEquals(WorkflowCheckpointService.PAUSED, paused2.getStatus());
        assertEquals("DRAWING", paused2.getStage());
        assertEquals(4, paused2.getRevision());

        WorkflowCheckpointEntity recovered2 = service.resumeRecovery(cp2.getCheckpointId(), 4);
        assertEquals(WorkflowCheckpointService.RUNNING, recovered2.getStatus());
        assertEquals("DRAWING", recovered2.getStage());
        assertEquals(5, recovered2.getRevision());

        WorkflowCheckpointEntity retried2 = service.resumeRecovery(cp2.getCheckpointId(), 5);
        assertEquals(WorkflowCheckpointService.RUNNING, retried2.getStatus());
        assertEquals("DRAWING", retried2.getStage());
        assertEquals(6, retried2.getRevision());
        assertCheckpointUnchanged(cp2.getCheckpointId(), WorkflowCheckpointService.RUNNING, "DRAWING", 6);

        // 4. Stale revision rejection in recovery without mutation
        AppException exStale = assertThrows(AppException.class, () -> service.resumeRecovery(cp2.getCheckpointId(), 99));
        assertEquals("CHECKPOINT_CONFLICT", exStale.getCode());
        assertCheckpointUnchanged(cp2.getCheckpointId(), WorkflowCheckpointService.RUNNING, "DRAWING", 6);

        AppException exStale2 = assertThrows(AppException.class, () -> service.resumeRecovery(cp2.getCheckpointId(), 5));
        assertEquals("CHECKPOINT_CONFLICT", exStale2.getCode());
        assertCheckpointUnchanged(cp2.getCheckpointId(), WorkflowCheckpointService.RUNNING, "DRAWING", 6);

        // 5. Invalid status rejection without mutation
        // WAITING_APPROVAL
        WorkflowCheckpointEntity cpWA = service.start("300000", "user-1", "session-wa", "prompt");
        service.approval(cpWA.getCheckpointId(), "inv-wa", "{\"rewrittenPrompt\":\"plan\"}");
        long revWA = service.get(cpWA.getCheckpointId()).getRevision();
        AppException exWA = assertThrows(AppException.class, () -> service.resumeRecovery(cpWA.getCheckpointId(), revWA));
        assertEquals("CHECKPOINT_STATE_MISMATCH", exWA.getCode());
        assertCheckpointUnchanged(cpWA.getCheckpointId(), WorkflowCheckpointService.WAITING_APPROVAL, "APPROVAL", revWA);

        // WAITING_TOOL_APPROVAL
        WorkflowCheckpointEntity cpWTA = service.start("300000", "user-1", "session-wta", "prompt");
        service.waitForToolApproval(cpWTA.getCheckpointId(), "inv-wta", "c1", "{}");
        long revWTA = service.get(cpWTA.getCheckpointId()).getRevision();
        AppException exWTA = assertThrows(AppException.class, () -> service.resumeRecovery(cpWTA.getCheckpointId(), revWTA));
        assertEquals("CHECKPOINT_STATE_MISMATCH", exWTA.getCode());
        assertCheckpointUnchanged(cpWTA.getCheckpointId(), WorkflowCheckpointService.WAITING_TOOL_APPROVAL, "TOOL_APPROVAL", revWTA);

        // COMPLETED
        WorkflowCheckpointEntity cpCompleted = service.start("300000", "user-1", "session-c", "prompt");
        service.finish(cpCompleted.getCheckpointId(), true, null);
        long revCompleted = service.get(cpCompleted.getCheckpointId()).getRevision();
        AppException exCompleted = assertThrows(AppException.class, () -> service.resumeRecovery(cpCompleted.getCheckpointId(), revCompleted));
        assertEquals("CHECKPOINT_STATE_MISMATCH", exCompleted.getCode());
        assertCheckpointUnchanged(cpCompleted.getCheckpointId(), WorkflowCheckpointService.COMPLETED, "TERMINAL", revCompleted);

        // CANCELLED
        WorkflowCheckpointEntity cpCancelled = service.start("300000", "user-1", "session-can", "prompt");
        service.cancel(cpCancelled.getCheckpointId());
        long revCancelled = service.get(cpCancelled.getCheckpointId()).getRevision();
        AppException exCancelled = assertThrows(AppException.class, () -> service.resumeRecovery(cpCancelled.getCheckpointId(), revCancelled));
        assertEquals("CHECKPOINT_STATE_MISMATCH", exCancelled.getCode());
        assertCheckpointUnchanged(cpCancelled.getCheckpointId(), WorkflowCheckpointService.CANCELLED, "TERMINAL", revCancelled);

        // FAILED
        WorkflowCheckpointEntity cpFailed = service.start("300000", "user-1", "session-f", "prompt");
        service.finish(cpFailed.getCheckpointId(), false, "error");
        long revFailed = service.get(cpFailed.getCheckpointId()).getRevision();
        AppException exFailed = assertThrows(AppException.class, () -> service.resumeRecovery(cpFailed.getCheckpointId(), revFailed));
        assertEquals("CHECKPOINT_STATE_MISMATCH", exFailed.getCode());
        assertCheckpointUnchanged(cpFailed.getCheckpointId(), WorkflowCheckpointService.FAILED, "TERMINAL", revFailed);

        // 6. Non-replayable stages under RUNNING and PAUSED without mutation
        WorkflowCheckpointEntity cpToolExec = service.start("300000", "user-1", "session-te", "prompt");
        service.waitForToolApproval(cpToolExec.getCheckpointId(), "inv-te", "call-1", "{}");
        service.resume(cpToolExec.getCheckpointId(), 2, "TOOL_APPROVE"); // status RUNNING, stage TOOL_EXECUTION, rev 3
        WorkflowCheckpointEntity toolExecEntity = service.get(cpToolExec.getCheckpointId());
        assertEquals("TOOL_EXECUTION", toolExecEntity.getStage());
        assertEquals(WorkflowCheckpointService.RUNNING, toolExecEntity.getStatus());

        AppException exToolExec = assertThrows(AppException.class, () -> service.resumeRecovery(cpToolExec.getCheckpointId(), 3));
        assertEquals("CHECKPOINT_STATE_MISMATCH", exToolExec.getCode());
        assertCheckpointUnchanged(cpToolExec.getCheckpointId(), WorkflowCheckpointService.RUNNING, "TOOL_EXECUTION", 3);

        service.pause(cpToolExec.getCheckpointId()); // status PAUSED, stage TOOL_EXECUTION, rev 4
        AppException exToolExecPaused = assertThrows(AppException.class, () -> service.resumeRecovery(cpToolExec.getCheckpointId(), 4));
        assertEquals("CHECKPOINT_STATE_MISMATCH", exToolExecPaused.getCode());
        assertCheckpointUnchanged(cpToolExec.getCheckpointId(), WorkflowCheckpointService.PAUSED, "TOOL_EXECUTION", 4);
    }

    @Test
    void shouldHandleCaseInsensitiveDecisionsWithWhitespace() {
        // " approve "
        WorkflowCheckpointEntity cp1 = service.start("300000", "user-1", "session-ci-1", "prompt");
        service.approval(cp1.getCheckpointId(), "inv-1", "{\"rewrittenPrompt\":\"plan\"}");
        WorkflowCheckpointEntity r1 = service.resume(cp1.getCheckpointId(), 2, "  approve  ");
        assertEquals(WorkflowCheckpointService.RUNNING, r1.getStatus());
        assertEquals("DRAWING", r1.getStage());

        // "Revise"
        WorkflowCheckpointEntity cp2 = service.start("300000", "user-1", "session-ci-2", "prompt");
        service.approval(cp2.getCheckpointId(), "inv-2", "{\"rewrittenPrompt\":\"plan\"}");
        WorkflowCheckpointEntity r2 = service.resume(cp2.getCheckpointId(), 2, "Revise");
        assertEquals(WorkflowCheckpointService.RUNNING, r2.getStatus());
        assertEquals("ANALYSIS", r2.getStage());

        // "tool_approve"
        WorkflowCheckpointEntity cp3 = service.start("300000", "user-1", "session-ci-3", "prompt");
        service.waitForToolApproval(cp3.getCheckpointId(), "inv-3", "c3", "{}");
        WorkflowCheckpointEntity r3 = service.resume(cp3.getCheckpointId(), 2, "tool_approve");
        assertEquals(WorkflowCheckpointService.RUNNING, r3.getStatus());
        assertEquals("TOOL_EXECUTION", r3.getStage());

        // "Continue"
        WorkflowCheckpointEntity cp4 = service.start("300000", "user-1", "session-ci-4", "prompt");
        service.pause(cp4.getCheckpointId());
        WorkflowCheckpointEntity r4 = service.resume(cp4.getCheckpointId(), 2, " Continue ");
        assertEquals(WorkflowCheckpointService.RUNNING, r4.getStatus());
    }

    private void assertCheckpointUnchanged(String id, String expectedStatus, String expectedStage, long expectedRevision) {
        WorkflowCheckpointEntity entity = service.get(id);
        assertEquals(expectedStatus, entity.getStatus(), "Status should remain unchanged");
        assertEquals(expectedStage, entity.getStage(), "Stage should remain unchanged");
        assertEquals(expectedRevision, entity.getRevision(), "Revision should remain unchanged");
    }

    static class InMemoryWorkflowCheckpointRepository implements IWorkflowCheckpointRepository {
        private final Map<String, WorkflowCheckpointEntity> store = new ConcurrentHashMap<>();

        @Override
        public WorkflowCheckpointEntity save(WorkflowCheckpointEntity checkpoint) {
            if (checkpoint == null) return null;
            WorkflowCheckpointEntity copy = cloneEntity(checkpoint);
            store.put(checkpoint.getCheckpointId(), copy);
            return cloneEntity(copy);
        }

        @Override
        public Optional<WorkflowCheckpointEntity> findById(String checkpointId) {
            WorkflowCheckpointEntity entity = store.get(checkpointId);
            return Optional.ofNullable(cloneEntity(entity));
        }

        private WorkflowCheckpointEntity cloneEntity(WorkflowCheckpointEntity src) {
            if (src == null) return null;
            return WorkflowCheckpointEntity.builder()
                    .checkpointId(src.getCheckpointId())
                    .invocationId(src.getInvocationId())
                    .agentId(src.getAgentId())
                    .userId(src.getUserId())
                    .sessionId(src.getSessionId())
                    .status(src.getStatus())
                    .stage(src.getStage())
                    .originalPrompt(src.getOriginalPrompt())
                    .approvalJson(src.getApprovalJson())
                    .pendingToolCallId(src.getPendingToolCallId())
                    .pendingToolConfirmationJson(src.getPendingToolConfirmationJson())
                    .errorMessage(src.getErrorMessage())
                    .revision(src.getRevision())
                    .createdAt(src.getCreatedAt())
                    .updatedAt(src.getUpdatedAt())
                    .build();
        }
    }
}
