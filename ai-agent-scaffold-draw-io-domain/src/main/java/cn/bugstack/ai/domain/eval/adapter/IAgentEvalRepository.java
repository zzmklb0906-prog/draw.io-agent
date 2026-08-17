package cn.bugstack.ai.domain.eval.adapter;

import java.util.List;
import java.util.Map;

public interface IAgentEvalRepository {
    List<Map<String,Object>> datasets(String username);
    Map<String,Object> dataset(String username,String datasetId);
    String createDataset(String username,String key,String name,String description);
    List<Map<String,Object>> cases(String username,String datasetId,boolean enabledOnly);
    String createCase(String username,String datasetId,Map<String,Object> definition);
    void updateCase(String username,String caseId,Map<String,Object> definition);
    String createRun(String username,String datasetId,String label,int repeats,String baselineRunId,int totalCases);
    void startRun(String runId);
    String createCaseRun(String runId,String caseId,long caseVersion,int repeatIndex);
    void startCaseRun(String caseRunId,String sessionId);
    void completeCaseRun(String caseRunId,String invocationId,String status,String output,double score,boolean passed,long duration,long input,long outputTokens,int tools,int models,Map<String,Object> breakdown,String error);
    void addAssertion(String caseRunId,Map<String,Object> assertion);
    void finishRun(String runId);
    void failRun(String runId,String error);
    void setBaseline(String username,String datasetId,String runId);
    List<Map<String,Object>> runs(String username,String datasetId,int limit);
    Map<String,Object> run(String username,String runId);
    List<Map<String,Object>> caseRuns(String username,String runId);
    List<Map<String,Object>> assertions(String username,String caseRunId);
    List<Map<String,Object>> evaluationsForInvocation(String username,String invocationId);
}
