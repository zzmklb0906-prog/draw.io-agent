package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.agent.adapter.repository.IContextSnapshotRepository;
import com.alibaba.fastjson.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcContextSnapshotRepository implements IContextSnapshotRepository {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public JdbcContextSnapshotRepository(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    @Override public Map<String,Object> enrichForInvocation(String invocation,Map<String,Object> state){Map<String,Object> enriched=new java.util.LinkedHashMap<>(state);var artifacts=jdbc.query("select a.id,a.artifact_type,a.name,a.branch_name,a.version_no from artifact a join agent_invocation i on i.conversation_id=a.conversation_id where i.id=? and a.artifact_type<>'TOOL_RESULT' order by a.created_at desc limit 1",(rs,n)->Map.of("artifactId",rs.getString(1),"artifactType",rs.getString(2),"name",rs.getString(3),"branch",rs.getString(4),"version",rs.getInt(5)),invocation);if(!artifacts.isEmpty()){enriched.put("current_artifact_id",artifacts.get(0).get("artifactId"));enriched.put("current_artifact",artifacts.get(0));}var toolArtifacts=jdbc.queryForList("select tool_name,result_artifact_id,result_summary from tool_execution where invocation_id=? and result_artifact_id is not null order by started_at",invocation);if(!toolArtifacts.isEmpty())enriched.put("tool_result_artifacts",toolArtifacts);return enriched;}
    @Override public void saveForInvocation(String invocation,String summary,Map<String,Object> state,int before,int after,String strategy,String model,long duration){tx.executeWithoutResult(status->{var rows=jdbc.query("select c.id,i.adk_session_id from agent_invocation i join conversation c on c.adk_session_id=i.adk_session_id where i.id=?",(rs,n)->Map.of("conversation",rs.getObject(1,UUID.class),"session",rs.getString(2)),invocation);if(rows.isEmpty())return;UUID conversation=(UUID)rows.get(0).get("conversation");String session=(String)rows.get(0).get("session");jdbc.queryForObject("select id from conversation where id=? for update",UUID.class,conversation);Integer version=jdbc.queryForObject("select coalesce(max(snapshot_version),0)+1 from context_snapshot where conversation_id=?",Integer.class,conversation);UUID id=UUID.randomUUID();jdbc.update("insert into context_snapshot(id,conversation_id,adk_session_id,snapshot_version,summary_text,structured_state,estimated_tokens,compression_strategy,compression_model) values (?,?,?,?,?,cast(? as jsonb),?,?,?)",id,conversation,session,version,summary,JSON.toJSONString(state),after,strategy,model);jdbc.update("update conversation set current_context_snapshot_id=?,updated_at=now(),version=version+1 where id=?",id,conversation);});}
}
