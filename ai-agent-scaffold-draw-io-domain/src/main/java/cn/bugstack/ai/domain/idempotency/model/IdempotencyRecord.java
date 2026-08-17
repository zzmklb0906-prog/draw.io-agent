package cn.bugstack.ai.domain.idempotency.model;

public record IdempotencyRecord(String owner,String scope,String key,String requestHash,String status,
                                String resourceId,String responseJson,int attemptCount) {}
