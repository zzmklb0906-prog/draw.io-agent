package cn.bugstack.ai.domain.agent.adapter.repository;

import java.util.Map;

public interface IContextSnapshotRepository {
    default Map<String,Object> enrichForInvocation(String invocationId,Map<String,Object> structuredState){return structuredState;}
    void saveForInvocation(String invocationId,String summary,Map<String,Object> structuredState,
                           int beforeTokens,int afterTokens,String strategy,String model,long durationMs);
}
