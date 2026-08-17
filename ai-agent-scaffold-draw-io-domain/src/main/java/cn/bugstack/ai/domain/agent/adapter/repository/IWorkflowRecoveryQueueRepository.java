package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.WorkflowRecoveryJob;
import java.util.Optional;
import java.util.UUID;

public interface IWorkflowRecoveryQueueRepository {
    Optional<WorkflowRecoveryJob> claim(String instanceId);
    void complete(UUID jobId,String recoveredInvocationId);
    void retryOrFail(UUID jobId,String error,int maxAttempts,long nextAttemptAt);
}
