package cn.bugstack.ai.domain.idempotency.adapter;

import cn.bugstack.ai.domain.idempotency.model.IdempotencyRecord;
import java.util.Optional;

public interface IIdempotencyRepository {
    boolean insertProcessing(String owner,String scope,String key,String requestHash,long expiresAt);
    Optional<IdempotencyRecord> find(String owner,String scope,String key);
    boolean retryFailed(String owner,String scope,String key,String requestHash,long expiresAt);
    void complete(String owner,String scope,String key,String resourceId,String responseJson);
    void fail(String owner,String scope,String key,String error);
}
