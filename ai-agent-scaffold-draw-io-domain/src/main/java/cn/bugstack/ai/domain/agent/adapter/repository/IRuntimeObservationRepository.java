package cn.bugstack.ai.domain.agent.adapter.repository;

import java.util.List;
import java.util.Map;

public interface IRuntimeObservationRepository {
    void invocationStarted(String invocationId,String sessionId,String userId,String rootAgent,long startedAt);
    default void invocationStarted(String invocationId,String sessionId,String userId,String rootAgent,String appName,long startedAt){invocationStarted(invocationId,sessionId,userId,rootAgent,startedAt);}
    void invocationCompleted(String invocationId,String status,long completedAt,long durationMs,long inputTokens,long outputTokens,String error);
    void bindInvocationRequest(String invocationId,String requestId);
    void invocationVersionSnapshot(String invocationId,Map<String,Object> snapshot);
    void invocationModelVersion(String invocationId,String modelName,String modelVersion);
    void workflowState(String sessionId,String checkpointId,String status);
    void agentStarted(String invocationId,String runId,String parentRunId,String agentName,String branch,long startedAt);
    void agentCompleted(String invocationId,String runId,String agentName,long completedAt,long durationMs,long modelCalls,long modelDurationMs,long inputTokens,long outputTokens);
    void modelStarted(String invocationId,String runId,String agentName,long startedAt,long estimatedInputTokens);
    default void modelStarted(String invocationId,String runId,String agentName,String modelName,long startedAt,long estimatedInputTokens){modelStarted(invocationId,runId,agentName,startedAt,estimatedInputTokens);}
    void modelCompleted(String invocationId,String runId,String agentName,long completedAt,long durationMs,long inputTokens,long outputTokens,String status,String error);
    void toolStarted(String invocationId,String runId,String agentName,String callId,String toolName,long startedAt,String argumentsJson);
    default void toolStarted(String invocationId,String runId,String agentName,String callId,String toolName,long startedAt,String argumentsJson,Map<String,Object> governancePolicy){toolStarted(invocationId,runId,agentName,callId,toolName,startedAt,argumentsJson);}
    void toolRetry(String invocationId,String callId,int attemptNo,long startedAt,String error);
    /** @return artifact id when a large Tool result was externalized, otherwise an empty string. */
    String toolCompleted(String invocationId,String callId,String status,long completedAt,long durationMs,String summary,int retryCount,String error);
    void capabilitySearch(String searchId,String invocationId,String runId,String agentName,String parentToolCallId,String snapshotId,String query,List<String> requestedTypes,int registrySize,List<Map<String,Object>> candidates,long startedAt,long completedAt,long durationMs,String status,String error);
    void capabilityExecutionStarted(String executionId,String invocationId,String runId,String agentName,String parentToolCallId,String snapshotId,String action,Map<String,Object> descriptor,String resourcePath,Map<String,Object> arguments,long startedAt);
    void capabilityExecutionCompleted(String executionId,String status,long completedAt,long durationMs,String resultSummary,long resultSize,String resultHash,int retryCount,String error);
    void runtimeEvent(String sessionId,String invocationId,String eventType,Map<String,Object> payload);
    List<Map<String,Object>> listBySession(String username,String sessionId);
    List<Map<String,Object>> listRecent(String username,int limit);
    Map<String,Object> detail(String username,String invocationId);
    Map<String,Object> executionStructure(String username,String invocationId);
    Map<String,Object> workflowDetail(String username,String taskId);
    List<Map<String,Object>> agentRuns(String username,String invocationId);
    List<Map<String,Object>> capabilitySearches(String username,String invocationId);
    List<Map<String,Object>> capabilityExecutions(String username,String invocationId);
    List<Map<String,Object>> waterfall(String username,String invocationId);
    void capabilityFeedback(String username,String invocationId,String searchId,String capabilityId,String judgment,String note);
    Map<String,Object> summary(String username);
    Map<String,Object> summary(String username,String sessionId);
}
