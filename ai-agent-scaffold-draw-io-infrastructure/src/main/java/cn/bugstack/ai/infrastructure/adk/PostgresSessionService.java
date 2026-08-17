package cn.bugstack.ai.infrastructure.adk;

import com.google.adk.events.Event;
import com.google.adk.sessions.*;
import io.reactivex.rxjava3.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@ConditionalOnProperty(name="ai.agent.persistence.mode",havingValue="postgres",matchIfMissing=true)
public class PostgresSessionService implements BaseSessionService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public PostgresSessionService(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    @Override public Single<Session> createSession(String app,String user,ConcurrentMap<String,Object> state,String requested){return Single.fromCallable(()->{String id=requested==null||requested.isBlank()?UUID.randomUUID().toString():requested;Session session=Session.builder(id).appName(app).userId(user).state(state==null?new ConcurrentHashMap<>():state).events(new ArrayList<>()).lastUpdateTime(Instant.now()).build();jdbc.update("insert into adk_session(session_id,app_name,user_key,state,session_json) values (?,?,?,cast(? as jsonb),?) on conflict(session_id) do update set session_json=excluded.session_json,updated_at=now()",id,app,user,"{}",session.toString());return session;});}
    @Override public Maybe<Session> getSession(String app,String user,String id,Optional<GetSessionConfig> config){return Maybe.fromCallable(()->jdbc.query("select session_json from adk_session where app_name=? and user_key=? and session_id=?",(rs,n)->Session.fromJson(rs.getString(1)),app,user,id).stream().findFirst().orElse(null));}
    @Override public Single<ListSessionsResponse> listSessions(String app,String user){return Single.fromCallable(()->ListSessionsResponse.builder().sessions(jdbc.query("select session_json from adk_session where app_name=? and user_key=? order by updated_at desc",(rs,n)->Session.fromJson(rs.getString(1)),app,user)).build());}
    @Override public Completable deleteSession(String app,String user,String id){return Completable.fromAction(()->jdbc.update("delete from adk_session where app_name=? and user_key=? and session_id=?",app,user,id));}
    @Override public Single<ListEventsResponse> listEvents(String app,String user,String id){return getSession(app,user,id,Optional.empty()).switchIfEmpty(Single.error(new IllegalStateException("Session 不存在: "+id))).map(s->ListEventsResponse.builder().events(s.events()).build());}
    @Override public Single<Event> appendEvent(Session session,Event event){return BaseSessionService.super.appendEvent(session,event).map(saved->{tx.executeWithoutResult(status->{Long version=jdbc.queryForObject("select version from adk_session where session_id=? for update",Long.class,session.id());long seq=(version==null?0:version)+1;jdbc.update("insert into adk_event(id,session_id,sequence_no,invocation_id,author,event_time,payload) values (?,?,?,?,?,?,cast(? as jsonb))",event.id(),session.id(),seq,event.invocationId(),event.author(),java.sql.Timestamp.from(Instant.now()),event.toString());session.lastUpdateTime(Instant.now());jdbc.update("update adk_session set session_json=?,updated_at=now(),version=? where session_id=?",session.toString(),seq,session.id());});return saved;});}
    @Override public Completable closeSession(Session session){return Completable.fromAction(()->jdbc.update("update adk_session set session_json=?,updated_at=now() where session_id=?",session.toString(),session.id()));}
}
