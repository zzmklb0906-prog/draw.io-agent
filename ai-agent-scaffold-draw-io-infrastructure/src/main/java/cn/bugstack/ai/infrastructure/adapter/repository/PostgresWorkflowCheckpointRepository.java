package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkflowCheckpointRepository;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowCheckpointEntity;
import com.alibaba.fastjson.JSON;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cn.bugstack.ai.types.exception.AppException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name="ai.agent.persistence.mode",havingValue="postgres",matchIfMissing=true)
public class PostgresWorkflowCheckpointRepository implements IWorkflowCheckpointRepository {
    private final JdbcTemplate jdbc;public PostgresWorkflowCheckpointRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public WorkflowCheckpointEntity save(WorkflowCheckpointEntity c){UUID id=UUID.fromString(c.getCheckpointId());int changed=jdbc.update("insert into workflow_checkpoint(id,adk_session_id,invocation_id,agent_id,user_id,stage,status,revision,state_snapshot,original_prompt,error_message,created_at,updated_at,entity_json) select ?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?, ?,cast(? as jsonb) from app_user where username=? on conflict(id) do update set invocation_id=excluded.invocation_id,stage=excluded.stage,status=excluded.status,revision=excluded.revision,state_snapshot=excluded.state_snapshot,original_prompt=excluded.original_prompt,error_message=excluded.error_message,updated_at=excluded.updated_at,entity_json=excluded.entity_json where workflow_checkpoint.revision=excluded.revision-1",id,c.getSessionId(),c.getInvocationId(),c.getAgentId(),userId(c.getUserId()),c.getStage(),c.getStatus(),c.getRevision(),c.getApprovalJson()==null?"{}":c.getApprovalJson(),c.getOriginalPrompt(),c.getErrorMessage(),new Timestamp(c.getCreatedAt()),new Timestamp(c.getUpdatedAt()),JSON.toJSONString(c),c.getUserId());if(changed!=1)throw new AppException("CHECKPOINT_CONFLICT","Checkpoint 已被其他请求更新，请刷新后重试");jdbc.update("update conversation set current_checkpoint_id=?,updated_at=now(),version=version+1 where adk_session_id=?",id,c.getSessionId());return c;}
    @Override public Optional<WorkflowCheckpointEntity> findById(String id){return jdbc.query("select entity_json from workflow_checkpoint where id=?",(rs,n)->JSON.parseObject(rs.getString(1),WorkflowCheckpointEntity.class),UUID.fromString(id)).stream().findFirst();}
    private UUID userId(String username){return jdbc.queryForObject("select id from app_user where username=?",UUID.class,username);}
}
