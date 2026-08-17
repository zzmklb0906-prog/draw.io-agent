package cn.bugstack.ai.infrastructure.persistence;

import cn.bugstack.ai.domain.idempotency.adapter.IIdempotencyRepository;
import cn.bugstack.ai.domain.idempotency.model.IdempotencyRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class JdbcIdempotencyRepository implements IIdempotencyRepository {
    private final JdbcTemplate jdbc;public JdbcIdempotencyRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public boolean insertProcessing(String owner,String scope,String key,String hash,long expiresAt){return jdbc.update("insert into idempotency_record(owner_key,operation_scope,idempotency_key,request_hash,status,expires_at) values (?,?,?,?,'PROCESSING',?) on conflict(owner_key,operation_scope,idempotency_key) do nothing",owner,scope,key,hash,new Timestamp(expiresAt))==1;}
    @Override public Optional<IdempotencyRecord> find(String owner,String scope,String key){return jdbc.query("select * from idempotency_record where owner_key=? and operation_scope=? and idempotency_key=?",(rs,n)->new IdempotencyRecord(rs.getString("owner_key"),rs.getString("operation_scope"),rs.getString("idempotency_key"),rs.getString("request_hash"),rs.getString("status"),rs.getString("resource_id"),rs.getString("response_json"),rs.getInt("attempt_count")),owner,scope,key).stream().findFirst();}
    @Override public boolean retryFailed(String owner,String scope,String key,String hash,long expiresAt){return jdbc.update("update idempotency_record set status='PROCESSING',error_message=null,attempt_count=attempt_count+1,updated_at=now(),expires_at=? where owner_key=? and operation_scope=? and idempotency_key=? and request_hash=? and status='FAILED'",new Timestamp(expiresAt),owner,scope,key,hash)==1;}
    @Override public void complete(String owner,String scope,String key,String resource,String response){jdbc.update("update idempotency_record set status='COMPLETED',resource_id=?,response_json=cast(? as jsonb),completed_at=now(),updated_at=now() where owner_key=? and operation_scope=? and idempotency_key=? and status='PROCESSING'",resource,response==null?"null":response,owner,scope,key);}
    @Override public void fail(String owner,String scope,String key,String error){jdbc.update("update idempotency_record set status='FAILED',error_message=?,updated_at=now() where owner_key=? and operation_scope=? and idempotency_key=? and status='PROCESSING'",error,owner,scope,key);}
}
