package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话接口
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/17 08:13
 */
public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    /** Executes an isolated benchmark turn without extracting long-term user memory. */
    List<String> handleMessageForEvaluation(String agentId, String userId, String sessionId, String message);

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String entryAgentName);
    Flowable<Event> handleMessageStream(String agentId,String userId,String sessionId,String message,String entryAgentName,String requestId);

    Flowable<Event> handleToolConfirmationStream(String agentId,String userId,String sessionId,String callId,boolean confirmed,java.util.Map<String,Object> payload,String requestId);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

}
