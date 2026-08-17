package cn.bugstack.ai.domain.identity.adapter;

import java.util.Map;

public interface ISecurityAuditRepository {
    void record(String username,String action,String resourceType,String resourceId,String outcome,String ipAddress,Map<String,Object> details);
}
