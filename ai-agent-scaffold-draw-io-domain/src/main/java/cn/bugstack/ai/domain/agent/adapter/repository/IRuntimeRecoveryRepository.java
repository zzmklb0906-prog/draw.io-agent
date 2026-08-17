package cn.bugstack.ai.domain.agent.adapter.repository;

public interface IRuntimeRecoveryRepository {
    void heartbeat(String instanceId, long now);
    void stop(String instanceId,long now);
    RecoveryReport recoverStale(String instanceId, long staleBefore, long invocationTimeoutBefore, long toolTimeoutBefore);

    record RecoveryReport(int interruptedInvocations, int queuedRecoveries, int timedOutInvocations, int reclaimedTools) {}
}
