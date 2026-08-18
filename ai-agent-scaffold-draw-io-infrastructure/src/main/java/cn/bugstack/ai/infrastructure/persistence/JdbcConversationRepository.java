package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.conversation.adapter.IConversationRepository;
import cn.bugstack.ai.domain.conversation.model.ConversationView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.alibaba.fastjson.JSON;
import java.time.Duration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcConversationRepository implements IConversationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    public JdbcConversationRepository(JdbcTemplate jdbc, TransactionTemplate tx,StringRedisTemplate redis) { this.jdbc=jdbc;this.tx=tx;this.redis=redis; }

    @Override public ConversationView create(String username,String agentId,String sessionId,String title){
        UUID workspaceId = ensureWorkspace(username);
        UUID id=UUID.randomUUID();
        int changed=jdbc.update("insert into conversation(id,workspace_id,user_id,agent_id,adk_session_id,title) select ?,?,u.id,?,?,? from app_user u where u.username=?",
                id,workspaceId,agentId,sessionId,blank(title,"新会话"),username);
        if(changed!=1)throw new IllegalArgumentException("用户不存在: "+username);
        return get(username,id).orElseThrow();
    }

    private UUID ensureWorkspace(String username) {
        UUID userId = jdbc.query("select id from app_user where username=?", (rs, row) -> rs.getObject("id", UUID.class), username)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));
        
        List<UUID> existing = jdbc.query("select w.id from agent_workspace w join workspace_member wm on wm.workspace_id=w.id where wm.user_id=? order by w.created_at limit 1",
                (rs, row) -> rs.getObject("id", UUID.class), userId);
        
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        UUID workspaceId = UUID.randomUUID();
        jdbc.update("insert into agent_workspace(id, owner_user_id, name, description) values(?, ?, ?, ?)",
                workspaceId, userId, username + " 的个人工作区", "系统自动创建的默认工作区");
        jdbc.update("insert into workspace_member(workspace_id, user_id, role) values(?, ?, 'OWNER') on conflict do nothing",
                workspaceId, userId);
        return workspaceId;
    }
    @Override public List<ConversationView> list(String username,int limit){
        ensureWorkspace(username);
        return jdbc.query("select c.*,case when c.status='RUNNING' then case when ci.status in ('SUCCESS','COMPLETED') then 'COMPLETED' when ci.status in ('ERROR','FAILED') then 'FAILED' when ci.status='CANCELLED' then 'CANCELLED' else coalesce((select w.status from workflow_checkpoint w where w.id=coalesce(c.current_checkpoint_id,(select w2.id from workflow_checkpoint w2 where w2.adk_session_id=c.adk_session_id order by w2.updated_at desc limit 1))),c.status) end else c.status end effective_status,coalesce(c.current_checkpoint_id,(select w.id from workflow_checkpoint w where w.adk_session_id=c.adk_session_id order by w.updated_at desc limit 1)) resolved_checkpoint_id,coalesce((select w.revision from workflow_checkpoint w where w.id=coalesce(c.current_checkpoint_id,(select w2.id from workflow_checkpoint w2 where w2.adk_session_id=c.adk_session_id order by w2.updated_at desc limit 1))),0) checkpoint_revision,(select count(*) from conversation_message m where m.conversation_id=c.id) message_count,t.tool_name active_tool_name,t.started_at active_tool_started_at,t.status active_tool_status from conversation c join workspace_member wm on wm.workspace_id=c.workspace_id join app_user u on u.id=wm.user_id left join agent_invocation ci on ci.id=c.current_invocation_id left join lateral(select tool_name,started_at,status from tool_execution where invocation_id=c.current_invocation_id and status='RUNNING' order by started_at desc limit 1)t on true where u.username=? and c.deleted_at is null order by coalesce(c.last_message_at,c.created_at) desc limit ?",
                this::mapConversation,username,Math.max(1,Math.min(limit,200)));
    }
    @Override public Optional<ConversationView> get(String username,UUID id){
        return jdbc.query("select c.*,case when c.status='RUNNING' then case when ci.status in ('SUCCESS','COMPLETED') then 'COMPLETED' when ci.status in ('ERROR','FAILED') then 'FAILED' when ci.status='CANCELLED' then 'CANCELLED' else coalesce((select w.status from workflow_checkpoint w where w.id=coalesce(c.current_checkpoint_id,(select w2.id from workflow_checkpoint w2 where w2.adk_session_id=c.adk_session_id order by w2.updated_at desc limit 1))),c.status) end else c.status end effective_status,coalesce(c.current_checkpoint_id,(select w.id from workflow_checkpoint w where w.adk_session_id=c.adk_session_id order by w.updated_at desc limit 1)) resolved_checkpoint_id,coalesce((select w.revision from workflow_checkpoint w where w.id=coalesce(c.current_checkpoint_id,(select w2.id from workflow_checkpoint w2 where w2.adk_session_id=c.adk_session_id order by w2.updated_at desc limit 1))),0) checkpoint_revision,(select count(*) from conversation_message m where m.conversation_id=c.id) message_count,t.tool_name active_tool_name,t.started_at active_tool_started_at,t.status active_tool_status from conversation c join workspace_member wm on wm.workspace_id=c.workspace_id join app_user u on u.id=wm.user_id left join agent_invocation ci on ci.id=c.current_invocation_id left join lateral(select tool_name,started_at,status from tool_execution where invocation_id=c.current_invocation_id and status='RUNNING' order by started_at desc limit 1)t on true where u.username=? and c.id=? and c.deleted_at is null",
                this::mapConversation,username,id).stream().findFirst().map(c->new ConversationView(c.id(),c.agentId(),c.sessionId(),c.title(),c.status(),c.currentInvocationId(),c.checkpointId(),c.checkpointRevision(),c.createdAt(),c.updatedAt(),c.messageCount(),c.activeToolName(),c.activeToolStartedAt(),c.activeToolStatus(),messages(id)));
    }
    @Override public ConversationView.MessageView append(String username,UUID id,String role,String type,String content,String contentJson,String invocationId){
        return append(username,id,role,type,content,contentJson,invocationId,null);
    }
    @Override public ConversationView.MessageView append(String username,UUID id,String role,String type,String content,String contentJson,String invocationId,String idempotencyKey){
        return tx.execute(status->{
            Integer owned=jdbc.queryForObject("select count(*) from conversation c join workspace_member wm on wm.workspace_id=c.workspace_id join app_user u on u.id=wm.user_id where c.id=? and u.username=? and wm.role in('OWNER','EDITOR') and c.deleted_at is null",Integer.class,id,username);
            if(owned==null||owned==0)throw new IllegalArgumentException("会话不存在或无权访问");
            jdbc.queryForObject("select id from conversation where id=? for update",UUID.class,id);
            if(idempotencyKey!=null&&!idempotencyKey.isBlank()){
                List<ConversationView.MessageView> existing=jdbc.query("select * from conversation_message where conversation_id=? and idempotency_key=?",this::mapMessage,id,idempotencyKey);
                if(!existing.isEmpty())return existing.get(0);
            }
            Long seq=jdbc.queryForObject("select coalesce(max(sequence_no),0)+1 from conversation_message where conversation_id=?",Long.class,id);
            UUID messageId=UUID.randomUUID();
            jdbc.update("insert into conversation_message(id,conversation_id,invocation_id,sequence_no,role,message_type,content_text,content_json,idempotency_key) values (?,?,?,?,?,?,?,cast(? as jsonb),?)",
                    messageId,id,invocationId,seq,role,blank(type,"TEXT"),content,contentJson==null?"null":contentJson,idempotencyKey);
            String generatedTitle="user".equalsIgnoreCase(role)&&content!=null?content.replaceAll("\\s+"," ").trim():"";
            if(generatedTitle.length()>48)generatedTitle=generatedTitle.substring(0,48)+"…";
            jdbc.update("update conversation set title=case when title='新会话' and ?<>'' then ? else title end,last_message_at=now(),updated_at=now(),version=version+1 where id=?",generatedTitle,generatedTitle,id);
            ConversationView.MessageView saved=jdbc.queryForObject("select * from conversation_message where id=?",this::mapMessage,messageId);
            String key="conversation:recent:"+id;redis.opsForList().leftPush(key,JSON.toJSONString(saved));redis.opsForList().trim(key,0,39);redis.expire(key,Duration.ofHours(24));
            return saved;
        });
    }
    @Override public void updateStatus(String username,UUID id,String status,String invocationId){jdbc.update("update conversation c set status=?,current_invocation_id=?,updated_at=now(),version=c.version+1 from workspace_member wm,app_user u where wm.workspace_id=c.workspace_id and wm.user_id=u.id and c.id=? and u.username=? and wm.role in('OWNER','EDITOR')",status,invocationId,id,username);}
    @Override public void delete(String username,UUID id){jdbc.update("update conversation c set deleted_at=now(),status='DELETED',updated_at=now() from workspace_member wm,app_user u where wm.workspace_id=c.workspace_id and wm.user_id=u.id and c.id=? and u.username=? and wm.role in('OWNER','EDITOR')",id,username);}
    private List<ConversationView.MessageView> messages(UUID id){return jdbc.query("select * from conversation_message where conversation_id=? order by sequence_no",this::mapMessage,id);}
    private ConversationView mapConversation(ResultSet rs,int row)throws SQLException{return new ConversationView(rs.getObject("id",UUID.class),rs.getString("agent_id"),rs.getString("adk_session_id"),rs.getString("title"),rs.getString("effective_status"),rs.getString("current_invocation_id"),rs.getString("resolved_checkpoint_id"),rs.getLong("checkpoint_revision"),instant(rs,"created_at"),instant(rs,"updated_at"),rs.getLong("message_count"),rs.getString("active_tool_name"),instant(rs,"active_tool_started_at"),rs.getString("active_tool_status"),List.of());}
    private ConversationView.MessageView mapMessage(ResultSet rs,int row)throws SQLException{return new ConversationView.MessageView(rs.getObject("id",UUID.class),rs.getLong("sequence_no"),rs.getString("role"),rs.getString("message_type"),rs.getString("content_text"),rs.getString("content_json"),rs.getString("invocation_id"),instant(rs,"created_at"));}
    private Instant instant(ResultSet rs,String name)throws SQLException{return rs.getTimestamp(name)==null?null:rs.getTimestamp(name).toInstant();}
    private String blank(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
}
