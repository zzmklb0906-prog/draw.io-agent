package cn.bugstack.ai.domain.agent.adapter.repository;

import java.util.List;
import java.util.Map;

public interface IDynamicSubagentRepository {
    List<Map<String,Object>> templates();
    Map<String,Object> template(String templateKey);
    String createTask(String invocationId,String parentRunId,String templateKey,String requestedBy,String taskText);
    default String createTask(String invocationId,String parentRunId,String parentTaskId,int depth,List<String> dependencies,String templateKey,String requestedBy,String taskText,long tokenBudget){return createTask(invocationId,parentRunId,templateKey,requestedBy,taskText);}
    void startTask(String taskId);
    void completeTask(String taskId,String childRunId,String result);
    default boolean completeTaskWithinBudget(String taskId,String childRunId,String result){completeTask(taskId,childRunId,result);return true;}
    void failTask(String taskId,String childRunId,String error);
    default void failTask(String taskId,String childRunId,String error,String partialResult){failTask(taskId,childRunId,error);}
    List<Map<String,Object>> tasks(String username,String invocationId);
}
