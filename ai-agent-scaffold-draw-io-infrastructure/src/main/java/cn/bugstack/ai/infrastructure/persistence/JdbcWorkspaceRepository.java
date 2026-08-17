package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.identity.adapter.IWorkspaceRepository;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Repository
public class JdbcWorkspaceRepository implements IWorkspaceRepository {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public JdbcWorkspaceRepository(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    @Override public List<Map<String,Object>> list(String username){return jdbc.query("select w.id,w.name,w.description,m.role,w.created_at,w.updated_at from agent_workspace w join workspace_member m on m.workspace_id=w.id join app_user u on u.id=m.user_id where u.username=? order by w.updated_at desc",(rs,n)->{Map<String,Object> x=new LinkedHashMap<>();x.put("id",rs.getString(1));x.put("name",rs.getString(2));x.put("description",Objects.toString(rs.getString(3),""));x.put("role",rs.getString(4));x.put("createdAt",rs.getTimestamp(5).getTime());x.put("updatedAt",rs.getTimestamp(6).getTime());return x;},username);}
    @Override public Map<String,Object> create(String username,String name,String description){String safe=name==null?"":name.trim();if(safe.isBlank()||safe.length()>160)throw new AppException("WORKSPACE_NAME_INVALID","工作区名称长度必须为 1-160");return tx.execute(status->{UUID id=UUID.randomUUID();int changed=jdbc.update("insert into agent_workspace(id,owner_user_id,name,description) select ?,id,?,? from app_user where username=?",id,safe,description,username);if(changed!=1)throw new AppException("AUTH_USER_NOT_FOUND","用户不存在");jdbc.update("insert into workspace_member(workspace_id,user_id,role) select ?,id,'OWNER' from app_user where username=?",id,username);return list(username).stream().filter(v->id.toString().equals(v.get("id"))).findFirst().orElseThrow();});}
    @Override public List<Map<String,Object>> members(String username,UUID workspace){requireMember(username,workspace);return jdbc.query("select u.username,u.display_name,m.role,m.created_at from workspace_member m join app_user u on u.id=m.user_id where m.workspace_id=? order by m.created_at",(rs,n)->Map.of("username",rs.getString(1),"displayName",rs.getString(2),"role",rs.getString(3),"createdAt",rs.getTimestamp(4).getTime()),workspace);}
    @Override public void putMember(String username,UUID workspace,String member,String role){requireOwner(username,workspace);String normalized=role==null?"":role.trim().toUpperCase(Locale.ROOT);if(!Set.of("OWNER","EDITOR","VIEWER").contains(normalized))throw new AppException("WORKSPACE_ROLE_INVALID","角色必须是 OWNER、EDITOR 或 VIEWER");int changed=jdbc.update("insert into workspace_member(workspace_id,user_id,role) select ?,id,? from app_user where username=? on conflict(workspace_id,user_id) do update set role=excluded.role",workspace,normalized,member);if(changed!=1)throw new AppException("WORKSPACE_MEMBER_NOT_FOUND","成员用户不存在");}
    @Override public void removeMember(String username,UUID workspace,String member){requireOwner(username,workspace);int changed=jdbc.update("delete from workspace_member m using app_user u,agent_workspace w where m.user_id=u.id and m.workspace_id=w.id and m.workspace_id=? and u.username=? and u.id<>w.owner_user_id",workspace,member);if(changed==0)throw new AppException("WORKSPACE_MEMBER_REMOVE_DENIED","成员不存在，或不能移除工作区所有者");}
    private void requireMember(String username,UUID workspace){if(jdbc.queryForObject("select count(*) from workspace_member m join app_user u on u.id=m.user_id where m.workspace_id=? and u.username=?",Integer.class,workspace,username)==0)throw new AppException("WORKSPACE_FORBIDDEN","无权访问该工作区");}
    private void requireOwner(String username,UUID workspace){if(jdbc.queryForObject("select count(*) from workspace_member m join app_user u on u.id=m.user_id where m.workspace_id=? and u.username=? and m.role='OWNER'",Integer.class,workspace,username)==0)throw new AppException("WORKSPACE_FORBIDDEN","仅工作区所有者可管理成员");}
}

