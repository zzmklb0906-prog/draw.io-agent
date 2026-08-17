package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeRecoveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

@Slf4j
@Component
public class RuntimeRecoveryScheduler {
    private final IRuntimeRecoveryRepository repository;
    private final String instanceId;
    @Value("${ai.agent.recovery.stale-ms:45000}") private long staleMs;
    @Value("${ai.agent.recovery.invocation-timeout-ms:900000}") private long invocationTimeoutMs;
    @Value("${ai.agent.recovery.tool-timeout-ms:180000}") private long toolTimeoutMs;
    public RuntimeRecoveryScheduler(IRuntimeRecoveryRepository repository,RuntimeInstanceIdentity identity){this.repository=repository;this.instanceId=identity.id();}

    @Scheduled(fixedDelayString="${ai.agent.recovery.heartbeat-ms:10000}",initialDelay=1000)
    public void heartbeat(){repository.heartbeat(instanceId,System.currentTimeMillis());}

    @Scheduled(fixedDelayString="${ai.agent.recovery.scan-ms:15000}",initialDelay=5000)
    public void recover(){long now=System.currentTimeMillis();var report=repository.recoverStale(instanceId,now-staleMs,now-invocationTimeoutMs,now-toolTimeoutMs);if(report.interruptedInvocations()+report.queuedRecoveries()+report.timedOutInvocations()+report.reclaimedTools()>0)log.warn("Runtime recovery: {}",report);}

    @PreDestroy public void stop(){try{repository.stop(instanceId,System.currentTimeMillis());}catch(Exception error){log.warn("Unable to mark runtime instance stopped: {}",error.getMessage());}}

}
