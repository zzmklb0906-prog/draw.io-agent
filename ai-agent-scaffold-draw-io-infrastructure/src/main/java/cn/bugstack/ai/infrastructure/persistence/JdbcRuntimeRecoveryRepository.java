package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeRecoveryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;

@Repository
public class JdbcRuntimeRecoveryRepository implements IRuntimeRecoveryRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JdbcRuntimeRecoveryRepository(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}

    @Override public void heartbeat(String instanceId,long now){
        Timestamp at=new Timestamp(now);
        jdbc.update("insert into runtime_instance(instance_id,started_at,heartbeat_at,status) values (?,?,?,'ACTIVE') on conflict(instance_id) do update set heartbeat_at=excluded.heartbeat_at,status='ACTIVE'",instanceId,at,at);
        jdbc.update("update agent_invocation set heartbeat_at=? where worker_instance_id=? and status='RUNNING'",at,instanceId);
    }
    @Override public void stop(String instanceId,long now){jdbc.update("update runtime_instance set status='STOPPED',heartbeat_at=? where instance_id=?",new Timestamp(now),instanceId);}

    @Override public RecoveryReport recoverStale(String instanceId,long staleBefore,long invocationTimeoutBefore,long toolTimeoutBefore){
        return tx.execute(status->{
            Timestamp stale=new Timestamp(staleBefore),invocationTimeout=new Timestamp(invocationTimeoutBefore),toolTimeout=new Timestamp(toolTimeoutBefore);
            int reclaimedAttempts=jdbc.update("update tool_execution_attempt a set status='FAILED',completed_at=now(),duration_ms=extract(epoch from(now()-a.started_at))*1000,error_message=coalesce(a.error_message,'Tool execution deadline expired') from tool_execution t where a.tool_execution_id=t.id and a.status='RUNNING' and t.status='RUNNING' and ((t.timeout_at is not null and t.timeout_at<now()) or (t.timeout_at is null and t.started_at<?))",toolTimeout);
            int reclaimedTools=jdbc.update("update tool_execution t set status='FAILED',completed_at=now(),duration_ms=extract(epoch from(now()-t.started_at))*1000,error_message=coalesce(t.error_message,'Tool execution deadline expired') where t.status='RUNNING' and ((t.timeout_at is not null and t.timeout_at<now()) or (t.timeout_at is null and t.started_at<?))",toolTimeout);

            int queued=jdbc.update("insert into workflow_recovery_job(checkpoint_id,source_invocation_id,status,reason) select w.id,i.id,'PENDING','WORKER_HEARTBEAT_EXPIRED' from agent_invocation i join conversation c on c.id=i.conversation_id join workflow_checkpoint w on w.id=c.current_checkpoint_id left join runtime_instance r on r.instance_id=i.worker_instance_id where i.status='RUNNING' and i.recoverable=true and coalesce(i.heartbeat_at,i.started_at)<? and (r.instance_id is null or r.heartbeat_at<?) and w.status='RUNNING' on conflict(source_invocation_id) do nothing",stale,stale);
            int interrupted=jdbc.update("update agent_invocation i set status='INTERRUPTED',completed_at=now(),duration_ms=extract(epoch from(now()-i.started_at))*1000,error_message=coalesce(i.error_message,'Worker heartbeat expired'),version=version+1 from runtime_instance r where i.status='RUNNING' and i.worker_instance_id=r.instance_id and i.heartbeat_at<? and r.heartbeat_at<?",stale,stale);
            interrupted+=jdbc.update("update agent_invocation i set status='INTERRUPTED',completed_at=now(),duration_ms=extract(epoch from(now()-i.started_at))*1000,error_message=coalesce(i.error_message,'Worker disappeared'),version=version+1 where i.status='RUNNING' and coalesce(i.heartbeat_at,i.started_at)<? and (i.worker_instance_id is null or not exists(select 1 from runtime_instance r where r.instance_id=i.worker_instance_id))",stale);
            int timedOut=jdbc.update("update agent_invocation set status='FAILED',completed_at=now(),duration_ms=extract(epoch from(now()-started_at))*1000,error_message=coalesce(error_message,'Invocation exceeded runtime timeout'),version=version+1 where status='RUNNING' and started_at<? and worker_instance_id<>?",invocationTimeout,instanceId);
            jdbc.update("update agent_run set status='ERROR',completed_at=now(),duration_ms=extract(epoch from(now()-started_at))*1000,error_message=coalesce(error_message,'Parent invocation interrupted'),version=version+1 where status='RUNNING' and invocation_id in(select id from agent_invocation where status in('INTERRUPTED','FAILED'))");
            jdbc.update("update agent_run_step set status='FAILED',completed_at=now(),duration_ms=extract(epoch from(now()-started_at))*1000,error_message=coalesce(error_message,'Parent invocation interrupted') where status='RUNNING' and invocation_id in(select id from agent_invocation where status in('INTERRUPTED','FAILED'))");
            jdbc.update("update agent_task set status='INTERRUPTED',completed_at=now(),updated_at=now(),version=version+1 where status='RUNNING' and current_invocation_id in(select id from agent_invocation where status='INTERRUPTED')");
            jdbc.update("update agent_task set status='FAILED',completed_at=now(),updated_at=now(),version=version+1 where status='RUNNING' and current_invocation_id in(select id from agent_invocation where status='FAILED')");
            jdbc.update("update workflow_checkpoint w set status='PAUSED',revision=revision+1,updated_at=now(),error_message=coalesce(error_message,'Queued for recovery after worker interruption'),entity_json=jsonb_set(jsonb_set(entity_json,'{status}','\"PAUSED\"'::jsonb),'{revision}',to_jsonb(revision+1)) where w.status='RUNNING' and exists(select 1 from workflow_recovery_job j where j.checkpoint_id=w.id and j.status='PENDING')");
            return new RecoveryReport(interrupted,queued,timedOut,Math.max(reclaimedTools,reclaimedAttempts));
        });
    }
}
