package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.identity.adapter.ISecurityAuditRepository;
import com.alibaba.fastjson.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Map;

@Repository
public class JdbcSecurityAuditRepository implements ISecurityAuditRepository {
    private final JdbcTemplate jdbc;public JdbcSecurityAuditRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public void record(String username,String action,String resourceType,String resourceId,String outcome,String ip,Map<String,Object> details){jdbc.update("insert into security_audit_event(user_id,username,action,resource_type,resource_id,outcome,ip_address,details) values ((select id from app_user where username=?),?,?,?,?,?,?,cast(? as jsonb))",username,username,action,resourceType,resourceId,outcome,ip,JSON.toJSONString(details==null?Map.of():details));}
}
