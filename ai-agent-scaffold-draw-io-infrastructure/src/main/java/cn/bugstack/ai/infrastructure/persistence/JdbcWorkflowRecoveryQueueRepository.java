package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkflowRecoveryQueueRepository;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowRecoveryJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkflowRecoveryQueueRepository implements IWorkflowRecoveryQueueRepository {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public JdbcWorkflowRecoveryQueueRepository(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    @Override public Optional<WorkflowRecoveryJob> claim(String instanceId){return tx.execute(status->{
        jdbc.update("update workflow_recovery_job j set status='FAILED',error_message=coalesce(error_message,'Checkpoint is no longer the conversation current checkpoint'),updated_at=now() where j.status='PENDING' and not exists(select 1 from workflow_checkpoint w join conversation c on c.current_checkpoint_id=w.id where w.id=j.checkpoint_id)");
        var ids=jdbc.query("select j.id from workflow_recovery_job j join workflow_checkpoint w on w.id=j.checkpoint_id join conversation c on c.current_checkpoint_id=w.id where j.status='PENDING' and j.available_at<=now() order by j.created_at for update of j skip locked limit 1",(rs,n)->rs.getObject(1,UUID.class));
        if(ids.isEmpty())return Optional.empty();UUID id=ids.get(0);
        jdbc.update("update workflow_recovery_job set status='RUNNING',claimed_by=?,claimed_at=now(),attempt_count=attempt_count+1,updated_at=now() where id=?",instanceId,id);
        return jdbc.query("select j.id,j.checkpoint_id,j.source_invocation_id,c.id conversation_id,u.username,w.agent_id,w.adk_session_id,w.stage,w.revision,w.original_prompt,w.entity_json->>'approvalJson' approval_json from workflow_recovery_job j join workflow_checkpoint w on w.id=j.checkpoint_id join conversation c on c.current_checkpoint_id=w.id join app_user u on u.id=c.user_id where j.id=?",(rs,n)->new WorkflowRecoveryJob(rs.getObject("id",UUID.class),rs.getString("checkpoint_id"),rs.getString("source_invocation_id"),rs.getObject("conversation_id",UUID.class),rs.getString("username"),rs.getString("agent_id"),rs.getString("adk_session_id"),rs.getString("stage"),rs.getLong("revision"),rs.getString("original_prompt"),rs.getString("approval_json")),id).stream().findFirst();
    });}
    @Override public void complete(UUID id,String invocation){jdbc.update("update workflow_recovery_job set status='COMPLETED',updated_at=now(),error_message=null where id=?",id);}
    @Override public void retryOrFail(UUID id,String error,int maxAttempts,long next){jdbc.update("update workflow_recovery_job set status=case when attempt_count>=? then 'FAILED' else 'PENDING' end,available_at=?,claimed_by=null,claimed_at=null,error_message=?,updated_at=now() where id=?",maxAttempts,new Timestamp(next),error,id);}
}
