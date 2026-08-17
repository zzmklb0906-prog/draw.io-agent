package cn.bugstack.ai.domain.idempotency.service;

import cn.bugstack.ai.domain.idempotency.adapter.IIdempotencyRepository;
import cn.bugstack.ai.domain.idempotency.model.IdempotencyRecord;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class IdempotencyService {
    private final IIdempotencyRepository repository;
    public IdempotencyService(IIdempotencyRepository repository){this.repository=repository;}

    public Claim begin(String owner,String scope,String key,String requestPayload){
        if(key==null||key.isBlank())return new Claim(true,false,null);
        if(key.length()>160)throw new AppException("IDEMPOTENCY_KEY_INVALID","幂等键长度不能超过 160");
        String hash=sha256(requestPayload==null?"":requestPayload);
        long expiresAt=System.currentTimeMillis()+Duration.ofHours(24).toMillis();
        if(repository.insertProcessing(owner,scope,key,hash,expiresAt))return new Claim(true,false,null);
        IdempotencyRecord existing=repository.find(owner,scope,key).orElseThrow();
        if(!hash.equals(existing.requestHash()))throw new AppException("IDEMPOTENCY_KEY_REUSED","同一幂等键不能用于不同请求");
        if("COMPLETED".equals(existing.status()))return new Claim(false,true,existing);
        if("FAILED".equals(existing.status())&&repository.retryFailed(owner,scope,key,hash,expiresAt))return new Claim(true,false,null);
        throw new AppException("IDEMPOTENCY_IN_PROGRESS","相同请求正在处理中，请稍后查询原任务");
    }

    public void complete(String owner,String scope,String key,String resourceId,String responseJson){if(key!=null&&!key.isBlank())repository.complete(owner,scope,key,resourceId,responseJson);}
    public void fail(String owner,String scope,String key,Throwable error){if(key!=null&&!key.isBlank())repository.fail(owner,scope,key,error==null?"unknown":String.valueOf(error.getMessage()));}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public record Claim(boolean acquired,boolean replay,IdempotencyRecord record){}
}
