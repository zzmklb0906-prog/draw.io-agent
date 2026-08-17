package cn.bugstack.ai.domain.agent.service.workflow;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkflowCheckpointRepository;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowCheckpointEntity;
import cn.bugstack.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WorkflowCheckpointService {

    public static final String RUNNING = "RUNNING";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String WAITING_TOOL_APPROVAL = "WAITING_TOOL_APPROVAL";
    public static final String PAUSED = "PAUSED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Resource
    private IWorkflowCheckpointRepository repository;

    public WorkflowCheckpointEntity start(String agentId, String userId, String sessionId, String prompt) {
        long now = System.currentTimeMillis();
        return repository.save(WorkflowCheckpointEntity.builder()
                .checkpointId(UUID.randomUUID().toString()).agentId(agentId).userId(userId).sessionId(sessionId)
                .status(RUNNING).stage("ANALYSIS").originalPrompt(prompt).revision(1).createdAt(now).updatedAt(now).build());
    }

    public WorkflowCheckpointEntity get(String id) {
        return repository.findById(id).orElseThrow(() -> new AppException("CHECKPOINT_NOT_FOUND", "Checkpoint 不存在或已被清理"));
    }

    public synchronized WorkflowCheckpointEntity approval(String id, String invocationId, String approvalJson) {
        WorkflowCheckpointEntity cp = get(id);
        cp.setInvocationId(invocationId);
        cp.setApprovalJson(approvalJson);
        cp.setStatus(WAITING_APPROVAL);
        cp.setStage("APPROVAL");
        return touch(cp);
    }

    public synchronized WorkflowCheckpointEntity resume(String id, long expectedRevision, String decision) {
        WorkflowCheckpointEntity cp = get(id);
        if (cp.getRevision() != expectedRevision) throw new AppException("CHECKPOINT_CONFLICT", "Checkpoint 已变化，请刷新后重试");
        if (CANCELLED.equals(cp.getStatus()) || COMPLETED.equals(cp.getStatus())) throw new AppException("CHECKPOINT_TERMINAL", "该工作流已结束，不能恢复");
        cp.setStatus(RUNNING);
        if ("REVISE".equalsIgnoreCase(decision)) {
            cp.setStage("ANALYSIS");
        } else if ("APPROVE".equalsIgnoreCase(decision)) {
            cp.setStage("DRAWING");
        } else if ("TOOL_APPROVE".equalsIgnoreCase(decision)||"TOOL_DENY".equalsIgnoreCase(decision)) {
            cp.setStage("TOOL_EXECUTION");
            cp.setPendingToolCallId(null);
            cp.setPendingToolConfirmationJson(null);
        } else if (!"CONTINUE".equalsIgnoreCase(decision)) {
            throw new AppException("CHECKPOINT_DECISION_INVALID", "不支持的 Checkpoint 恢复决策");
        }
        return touch(cp);
    }

    public synchronized WorkflowCheckpointEntity waitForToolApproval(String id,String invocationId,String callId,String confirmationJson){
        WorkflowCheckpointEntity cp=get(id);cp.setInvocationId(invocationId);cp.setPendingToolCallId(callId);cp.setPendingToolConfirmationJson(confirmationJson);cp.setStatus(WAITING_TOOL_APPROVAL);cp.setStage("TOOL_APPROVAL");return touch(cp);
    }

    public synchronized WorkflowCheckpointEntity pause(String id) {
        WorkflowCheckpointEntity cp = get(id);
        if (!COMPLETED.equals(cp.getStatus()) && !CANCELLED.equals(cp.getStatus())) cp.setStatus(PAUSED);
        return touch(cp);
    }

    public synchronized WorkflowCheckpointEntity cancel(String id) {
        WorkflowCheckpointEntity cp = get(id);
        cp.setStatus(CANCELLED);
        cp.setStage("TERMINAL");
        return touch(cp);
    }

    public synchronized void finish(String id, boolean success, String error) {
        WorkflowCheckpointEntity cp = get(id);
        if (WAITING_APPROVAL.equals(cp.getStatus()) || WAITING_TOOL_APPROVAL.equals(cp.getStatus()) || PAUSED.equals(cp.getStatus()) || CANCELLED.equals(cp.getStatus())) return;
        cp.setStatus(success ? COMPLETED : FAILED);
        cp.setStage("TERMINAL");
        cp.setErrorMessage(error);
        touch(cp);
    }

    private WorkflowCheckpointEntity touch(WorkflowCheckpointEntity cp) {
        cp.setRevision(cp.getRevision() + 1);
        cp.setUpdatedAt(System.currentTimeMillis());
        return repository.save(cp);
    }
}
