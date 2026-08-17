package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.agent.memory.adapter.repository.IMemoryRepository;
import cn.bugstack.ai.domain.agent.memory.model.entity.AgentMemoryEntity;
import cn.bugstack.ai.domain.agent.memory.model.entity.MemoryEvidenceEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name="ai.agent.persistence.mode", havingValue="postgres", matchIfMissing=true)
public class JdbcMemoryRepository implements IMemoryRepository {
    private final JdbcTemplate jdbc;
    public JdbcMemoryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override @Transactional
    public AgentMemoryEntity insert(AgentMemoryEntity m){
        jdbc.update("insert into agent_memory(memory_id,user_key,project_id,memory_type,content,structured_data,importance,confidence,confirmed,source_session_id,source_event_id,created_at,updated_at,expires_at,deleted) values (?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,?,?,?)",
                m.getMemoryId(),m.getUserId(),m.getProjectId(),m.getMemoryType(),m.getContent(),json(m.getStructuredData()),m.getImportance(),m.getConfidence(),m.isConfirmed(),m.getSourceSessionId(),m.getSourceEventId(),ts(m.getCreatedAt()),ts(m.getUpdatedAt()),ts(m.getExpiresAt()),m.isDeleted());
        if(present(m.getSourceSessionId())) addEvidence(m.getMemoryId(),"SESSION",m.getSourceSessionId(),m.getCreatedAt());
        if(present(m.getSourceEventId())) addEvidence(m.getMemoryId(),"EVENT",m.getSourceEventId(),m.getCreatedAt());
        return m;
    }
    @Override public Optional<AgentMemoryEntity> findById(String id){return jdbc.query("select * from agent_memory where memory_id=? and deleted=false",this::map,id).stream().findFirst();}
    @Override public List<AgentMemoryEntity> find(String user,String project,String type,int limit){return jdbc.query("select * from agent_memory where user_key=? and deleted=false and (cast(? as varchar) is null or project_id=?) and (cast(? as varchar) is null or memory_type=?) and (expires_at is null or expires_at>now()) order by confirmed desc,importance desc,updated_at desc limit ?",this::map,user,project,project,type,type,bound(limit));}
    @Override public List<AgentMemoryEntity> search(String user,String project,String query,int limit){
        String value=query==null?"":query.trim();
        if(value.isEmpty()) return find(user,project,null,limit);
        String like="%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return jdbc.query("select * from agent_memory where user_key=? and deleted=false and (cast(? as varchar) is null or project_id is null or project_id=?) and (expires_at is null or expires_at>now()) and (search_document @@ websearch_to_tsquery('simple',?) or content ilike ? escape '!' or structured_data::text ilike ? escape '!') order by confirmed desc,importance desc,confidence desc,updated_at desc limit ?",this::map,user,project,project,value,like,like,bound(limit));
    }
    @Override public boolean update(AgentMemoryEntity m,long expected){return jdbc.update("update agent_memory set content=?,structured_data=cast(? as jsonb),importance=?,confidence=?,confirmed=?,source_session_id=?,source_event_id=?,updated_at=?,expires_at=?,deleted=? where memory_id=? and updated_at=?",m.getContent(),json(m.getStructuredData()),m.getImportance(),m.getConfidence(),m.isConfirmed(),m.getSourceSessionId(),m.getSourceEventId(),ts(m.getUpdatedAt()),ts(m.getExpiresAt()),m.isDeleted(),m.getMemoryId(),ts(expected))==1;}
    @Override public List<MemoryEvidenceEntity> findEvidence(String memoryId){return jdbc.query("select * from agent_memory_evidence where memory_id=? order by created_at",(r,n)->MemoryEvidenceEntity.builder().memoryId(r.getString("memory_id")).evidenceType(r.getString("evidence_type")).evidenceId(r.getString("evidence_id")).createdAt(r.getTimestamp("created_at").toInstant().toEpochMilli()).build(),memoryId);}
    @Override public void addEvidence(String memoryId,String type,String id,long at){jdbc.update("insert into agent_memory_evidence(memory_id,evidence_type,evidence_id,created_at) values (?,?,?,?) on conflict do nothing",memoryId,type,id,ts(at));}
    private AgentMemoryEntity map(ResultSet r,int row)throws SQLException{Timestamp expires=r.getTimestamp("expires_at");return AgentMemoryEntity.builder().memoryId(r.getString("memory_id")).userId(r.getString("user_key")).projectId(r.getString("project_id")).memoryType(r.getString("memory_type")).content(r.getString("content")).structuredData(r.getString("structured_data")).importance(r.getDouble("importance")).confidence(r.getDouble("confidence")).confirmed(r.getBoolean("confirmed")).sourceSessionId(r.getString("source_session_id")).sourceEventId(r.getString("source_event_id")).createdAt(r.getTimestamp("created_at").toInstant().toEpochMilli()).updatedAt(r.getTimestamp("updated_at").toInstant().toEpochMilli()).expiresAt(expires==null?null:expires.toInstant().toEpochMilli()).deleted(r.getBoolean("deleted")).build();}
    private static Timestamp ts(Long value){return value==null?null:Timestamp.from(Instant.ofEpochMilli(value));}
    private static String json(String value){return value==null||value.isBlank()?"{}":value;}
    private static boolean present(String value){return value!=null&&!value.isBlank();}
    private static int bound(int value){return Math.max(1,Math.min(value,200));}
}
