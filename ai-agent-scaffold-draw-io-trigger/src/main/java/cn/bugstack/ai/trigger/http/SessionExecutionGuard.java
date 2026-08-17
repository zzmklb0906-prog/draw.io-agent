package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.types.exception.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Cross-instance single-flight guard for one ADK session. */
@Component
public class SessionExecutionGuard {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class);
    private final StringRedisTemplate redis;
    private final Duration ttl;
    public SessionExecutionGuard(StringRedisTemplate redis,@Value("${ai.agent.session-lock-ttl-seconds:1800}")long seconds){this.redis=redis;this.ttl=Duration.ofSeconds(Math.max(60,seconds));}
    public Lease acquire(String username,String sessionId){String key="agent:session:lease:"+username+":"+sessionId,token=UUID.randomUUID().toString();Boolean ok=redis.opsForValue().setIfAbsent(key,token,ttl);if(!Boolean.TRUE.equals(ok))throw new AppException("SESSION_BUSY","该会话已有任务正在运行，请等待完成或停止后重试");return new Lease(key,token);}
    public void release(Lease lease){if(lease!=null)redis.execute(RELEASE,List.of(lease.key()),lease.token());}
    public record Lease(String key,String token){}
}
