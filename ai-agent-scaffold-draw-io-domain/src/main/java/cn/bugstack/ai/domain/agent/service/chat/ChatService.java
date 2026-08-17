package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.FunctionResponse;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import cn.bugstack.ai.domain.agent.memory.service.ConversationMemoryExtractionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private ConversationMemoryExtractionService conversationMemoryExtractionService;

    @Value("${ai.agent.chat.streaming-enabled:false}")
    private boolean streamingEnabled;
    @Value("${ai.agent.chat.execution-timeout-ms:900000}")
    private long executionTimeoutMs;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        // ADK 的二参数便捷方法会把 state 以 null 传给 SessionService。
        // 持久化 Session 实现以及 ADK 1.7 的 Session.Builder 都要求 state 非空。
        Session session = runner.sessionService().createSession(
                        appName, userId, new ConcurrentHashMap<>(), null)
                .blockingGet();
        
        String sessionId = session.id();
        // Update cache so subsequent handleMessage calls without sessionId can use this new session
        String cacheKey = userId + "_" + agentId;
        userSessions.put(cacheKey, sessionId);
        
        return sessionId;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String cacheKey = userId + "_" + agentId;
        String sessionId = userSessions.get(cacheKey);
        if (sessionId == null) {
            sessionId = createSession(agentId, userId);
        }

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        return executeMessage(agentId, userId, sessionId, message, true);
    }

    @Override
    public List<String> handleMessageForEvaluation(String agentId, String userId, String sessionId, String message) {
        return executeMessage(agentId, userId, sessionId, message, false);
    }

    private List<String> executeMessage(String agentId, String userId, String sessionId, String message, boolean extractMemory) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg).timeout(Math.max(1000,executionTimeoutMs),TimeUnit.MILLISECONDS);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));
        if (extractMemory) conversationMemoryExtractionService.extractExplicitStatement(userId,sessionId,message);

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return handleMessageStream(agentId,userId,sessionId,message,null);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String entryAgentName) {
        return handleMessageStream(agentId,userId,sessionId,message,entryAgentName,null);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String entryAgentName,String requestId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.runnerFor(entryAgentName);

        Content userMsg = Content.fromParts(Part.fromText(message));
        // HTTP 仍使用 NDJSON 传输状态、Tool 和最终结果；模型文本是否逐 token 输出独立配置。
        RunConfig runConfig = RunConfig.builder().setStreamingMode(
                streamingEnabled ? RunConfig.StreamingMode.SSE : RunConfig.StreamingMode.NONE).customMetadata(requestId==null?Map.of():Map.of("platformRequestId",requestId)).build();
        return runner.runAsync(userId, sessionId, userMsg, runConfig).timeout(Math.max(1000,executionTimeoutMs),TimeUnit.MILLISECONDS).doOnComplete(()->conversationMemoryExtractionService.extractExplicitStatement(userId,sessionId,message));
    }

    @Override
    public Flowable<Event> handleToolConfirmationStream(String agentId,String userId,String sessionId,String callId,boolean confirmed,Map<String,Object> payload,String requestId){
        AiAgentRegisterVO register=defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if(register==null)throw new AppException(ResponseCode.E0001.getCode());
        FunctionResponse response=FunctionResponse.builder().name("adk_request_confirmation").id(callId)
                .response(Map.of("confirmed",confirmed,"payload",payload==null?Map.of():payload)).build();
        Content content=Content.builder().role("user").parts(List.of(Part.builder().functionResponse(response).build())).build();
        RunConfig config=RunConfig.builder().setStreamingMode(streamingEnabled?RunConfig.StreamingMode.SSE:RunConfig.StreamingMode.NONE).customMetadata(requestId==null?Map.of():Map.of("platformRequestId",requestId)).build();
        return register.getRunner().runAsync(userId,sessionId,content,config).timeout(Math.max(1000,executionTimeoutMs),TimeUnit.MILLISECONDS);
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        Runner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content).timeout(Math.max(1000,executionTimeoutMs),TimeUnit.MILLISECONDS);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));
        StringBuilder plain=new StringBuilder();for(ChatCommandEntity.Content.Text text:texts==null?List.<ChatCommandEntity.Content.Text>of():texts)plain.append(text.getMessage()).append('\n');conversationMemoryExtractionService.extractExplicitStatement(chatCommandEntity.getUserId(),chatCommandEntity.getSessionId(),plain.toString());

        return outputs;
    }

}
