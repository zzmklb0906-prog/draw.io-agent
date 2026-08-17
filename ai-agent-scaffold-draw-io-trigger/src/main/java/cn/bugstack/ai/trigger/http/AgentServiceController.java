package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowCheckpointEntity;
import cn.bugstack.ai.domain.agent.service.workflow.WorkflowCheckpointService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.bugstack.ai.domain.agent.service.chat.CustomApiConfigManager;
import org.apache.commons.lang3.StringUtils;
import cn.bugstack.ai.trigger.http.auth.AuthenticatedUserContext;
import cn.bugstack.ai.domain.conversation.adapter.IConversationRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeObservationRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IDynamicSubagentRepository;
import cn.bugstack.ai.domain.artifact.adapter.IArtifactRepository;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import cn.bugstack.ai.domain.eval.service.AgentEvalService;
import cn.bugstack.ai.domain.idempotency.service.IdempotencyService;

/**
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class AgentServiceController implements IAgentService {

    @Resource
    private SessionExecutionGuard sessionExecutionGuard;

    @Resource
    private IChatService chatService;

    @Resource
    private LightweightMonitorService lightweightMonitorService;

    @Resource
    private WorkflowCheckpointService workflowCheckpointService;

    @Resource
    private IConversationRepository conversationRepository;

    @Resource
    private IRuntimeObservationRepository runtimeObservationRepository;
    @Resource
    private IDynamicSubagentRepository dynamicSubagentRepository;

    @Resource
    private IArtifactRepository artifactRepository;

    @Resource
    private CapabilityRegistryService capabilityRegistryService;
    @Resource
    private AgentEvalService agentEvalService;
    @Resource
    private IdempotencyService idempotencyService;

    @GetMapping("workflows/{checkpointId}")
    public Response<WorkflowCheckpointEntity> workflowCheckpoint(@PathVariable String checkpointId) {
        WorkflowCheckpointEntity checkpoint=workflowCheckpointService.get(checkpointId);AuthenticatedUserContext.require(checkpoint.getUserId());return Response.<WorkflowCheckpointEntity>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(checkpoint).build();
    }

    @PostMapping("workflows/{checkpointId}/pause")
    public Response<WorkflowCheckpointEntity> pauseWorkflow(@PathVariable String checkpointId) {
        WorkflowCheckpointEntity existing=workflowCheckpointService.get(checkpointId);AuthenticatedUserContext.require(existing.getUserId());WorkflowCheckpointEntity saved=workflowCheckpointService.pause(checkpointId);runtimeObservationRepository.workflowState(saved.getSessionId(),saved.getCheckpointId(),saved.getStatus());return workflowResponse(saved);
    }

    @PostMapping("workflows/{checkpointId}/cancel")
    public Response<WorkflowCheckpointEntity> cancelWorkflow(@PathVariable String checkpointId) {
        WorkflowCheckpointEntity existing=workflowCheckpointService.get(checkpointId);AuthenticatedUserContext.require(existing.getUserId());WorkflowCheckpointEntity saved=workflowCheckpointService.cancel(checkpointId);runtimeObservationRepository.workflowState(saved.getSessionId(),saved.getCheckpointId(),saved.getStatus());return workflowResponse(saved);
    }

    private Response<WorkflowCheckpointEntity> workflowResponse(WorkflowCheckpointEntity checkpoint) {
        return Response.<WorkflowCheckpointEntity>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(checkpoint).build();
    }

    @GetMapping("monitor/summary")
    public Response<Map<String, Object>> monitorSummary(@RequestParam(required = false) String sessionId) {
        Map<String,Object> live=lightweightMonitorService.summary(AuthenticatedUserContext.current());
        Map<String,Object> persisted=runtimeObservationRepository.summary(AuthenticatedUserContext.current(),sessionId);
        Map<String,Object> data=new java.util.LinkedHashMap<>(persisted);
        data.put("registeredTools",live.getOrDefault("registeredTools",java.util.List.of()));
        data.put("registeredCapabilities",capabilityRegistryService.size());
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    @GetMapping("monitor/invocations")
    public Response<List<Map<String, Object>>> monitorInvocations() {
        List<Map<String,Object>> live=lightweightMonitorService.list(AuthenticatedUserContext.current());
        List<Map<String,Object>> persisted=runtimeObservationRepository.listRecent(AuthenticatedUserContext.current(),200);
        java.util.LinkedHashMap<String,Map<String,Object>> merged=new java.util.LinkedHashMap<>();live.forEach(item->merged.put(String.valueOf(item.get("invocationId")),item));persisted.forEach(item->merged.putIfAbsent(String.valueOf(item.get("invocationId")),item));
        return Response.<List<java.util.Map<String, Object>>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(new java.util.ArrayList<>(merged.values()))
                .build();
    }

    @GetMapping("monitor/invocations/{invocationId}")
    public Response<Map<String, Object>> monitorInvocation(@PathVariable("invocationId") String invocationId) {
        try {
            Map<String,Object> live=lightweightMonitorService.detail(invocationId,AuthenticatedUserContext.current());
            Map<String,Object> persisted=runtimeObservationRepository.detail(AuthenticatedUserContext.current(),invocationId);
            Map<String,Object> structure=runtimeObservationRepository.executionStructure(AuthenticatedUserContext.current(),invocationId);
            if(!structure.isEmpty()){persisted=new java.util.LinkedHashMap<>(persisted);persisted.putAll(structure);}
            if(!persisted.isEmpty()){persisted=new java.util.LinkedHashMap<>(persisted);persisted.put("agentRuns",runtimeObservationRepository.agentRuns(AuthenticatedUserContext.current(),invocationId));persisted.put("waterfall",runtimeObservationRepository.waterfall(AuthenticatedUserContext.current(),invocationId));persisted.put("subagentTasks",dynamicSubagentRepository.tasks(AuthenticatedUserContext.current(),invocationId));persisted.put("capabilitySearches",runtimeObservationRepository.capabilitySearches(AuthenticatedUserContext.current(),invocationId));persisted.put("capabilityExecutions",runtimeObservationRepository.capabilityExecutions(AuthenticatedUserContext.current(),invocationId));persisted.put("evaluations",agentEvalService.forInvocation(AuthenticatedUserContext.current(),invocationId));}
            Map<String,Object> data;
            if(live.isEmpty()) data=persisted;
            else {
                data=new java.util.LinkedHashMap<>(persisted);
                data.putAll(live);
                // Persistent projections contain arguments, attempts and one row per model call.
                if(persisted.get("models") instanceof java.util.List<?> models&&!models.isEmpty())data.put("models",models);
                if(persisted.get("tools") instanceof java.util.List<?> tools&&!tools.isEmpty())data.put("tools",tools);
                if(persisted.get("events") instanceof java.util.List<?> events&&!events.isEmpty())data.put("events",events);
            }
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception error) {
            log.error("查询 Invocation 详情失败 invocationId:{}", invocationId, error);
            throw error;
        }
    }

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        boolean idempotencyAcquired=false;
        try {
            AuthenticatedUserContext.require(requestDTO.getUserId());
            String requestFingerprint=requestDTO.getAgentId()+"|"+requestDTO.getUserId();
            IdempotencyService.Claim claim=idempotencyService.begin(requestDTO.getUserId(),"CREATE_SESSION",requestDTO.getIdempotencyKey(),requestFingerprint);
            idempotencyAcquired=claim.acquired();
            if(claim.replay()){
                CreateSessionResponseDTO replay=JSON.parseObject(claim.record().responseJson(),CreateSessionResponseDTO.class);
                return Response.<CreateSessionResponseDTO>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(replay).build();
            }
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);
            responseDTO.setConversationId(conversationRepository.create(requestDTO.getUserId(),requestDTO.getAgentId(),sessionId,"新会话").id().toString());
            idempotencyService.complete(requestDTO.getUserId(),"CREATE_SESSION",requestDTO.getIdempotencyKey(),responseDTO.getConversationId(),JSON.toJSONString(responseDTO));

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            if(idempotencyAcquired)idempotencyService.fail(requestDTO.getUserId(),"CREATE_SESSION",requestDTO.getIdempotencyKey(),e);
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            if(idempotencyAcquired)idempotencyService.fail(requestDTO.getUserId(),"CREATE_SESSION",requestDTO.getIdempotencyKey(),e);
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            AuthenticatedUserContext.require(requestDTO.getUserId());
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
                requestDTO.setSessionId(sessionId);
            }

            // 保存用户自定义配置
            CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                    .baseUrl(requestDTO.getCustomBaseUrl())
                    .apiKey(requestDTO.getCustomApiKey())
                    .completionsPath(requestDTO.getCustomCompletionsPath())
                    .model(requestDTO.getCustomModel())
                    .customModelSelected(StringUtils.isNotBlank(requestDTO.getCustomModel()))
                    .build();
            CustomApiConfigManager.setConfig(sessionId, config);

            List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            try {
                // 尝试获取最后一条消息并解析
                String result = messages.stream().reduce((first, second) -> second).orElse("");
                ChatResponseDTO parsed = JSON.parseObject(result, ChatResponseDTO.class);
                if (null != parsed) {
                    responseDTO = parsed;
                    // 如果解析后的对象 type 为空，则默认为 user
                    if (null == responseDTO.getType()) {
                        responseDTO.setType("user");
                    }
                } else {
                    responseDTO.setType("user");
                    responseDTO.setContent(String.join("\n", messages));
                }
            } catch (Exception e) {
                responseDTO.setType("user");
                responseDTO.setContent(String.join("\n", messages));
            }

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        } finally {
            CustomApiConfigManager.clearConfig(requestDTO.getSessionId());
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        final java.util.concurrent.atomic.AtomicReference<SessionExecutionGuard.Lease> executionLease = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean streamIdempotencyAcquired = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicReference<String> streamIdempotencyScope = new java.util.concurrent.atomic.AtomicReference<>();
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(30 * 60 * 1000L) {
            @Override
            protected void extendResponse(org.springframework.http.server.ServerHttpResponse outputMessage) {
                outputMessage.getHeaders().set("Content-Type", "application/x-ndjson");
            }
        };
        try {
            AuthenticatedUserContext.require(requestDTO.getUserId());
            String operationScope=StringUtils.isBlank(requestDTO.getCheckpointId())?"SUBMIT_MESSAGE":"CHECKPOINT_DECISION";
            streamIdempotencyScope.set(operationScope);
            IdempotencyService.Claim streamClaim=idempotencyService.begin(requestDTO.getUserId(),operationScope,requestDTO.getIdempotencyKey(),JSON.toJSONString(requestDTO));
            streamIdempotencyAcquired.set(streamClaim.acquired());
            if(streamClaim.replay()){
                com.alibaba.fastjson.JSONObject envelope=new com.alibaba.fastjson.JSONObject();envelope.put("phase","done");
                com.alibaba.fastjson.JSONObject chunk=new com.alibaba.fastjson.JSONObject();chunk.put("type","done");chunk.put("content","该请求已处理，未重复执行。");chunk.put("idempotentReplay",true);chunk.put("resourceId",streamClaim.record().resourceId());envelope.put("chunk",chunk);
                emitter.send(envelope.toJSONString()+"\n");emitter.complete();return emitter;
            }
            log.info("流式对话 agentId:{} userId:{} sessionId:{} message:{}", requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(), requestDTO.getMessage());

            // Ensure session exists
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
                requestDTO.setSessionId(sessionId);
            }

            final String finalSessionId = sessionId;
            executionLease.set(sessionExecutionGuard.acquire(requestDTO.getUserId(), finalSessionId));

            final WorkflowCheckpointEntity checkpoint;
            String effectiveMessage = requestDTO.getMessage();
            String entryAgentName = null;
            if (StringUtils.isNotBlank(requestDTO.getCheckpointId())) {
                WorkflowCheckpointEntity existingCheckpoint=workflowCheckpointService.get(requestDTO.getCheckpointId());
                if (!finalSessionId.equals(existingCheckpoint.getSessionId()) || !requestDTO.getUserId().equals(existingCheckpoint.getUserId())) {
                    throw new AppException("CHECKPOINT_OWNER_MISMATCH", "Checkpoint 与当前会话不匹配");
                }
                String pendingToolCallId=existingCheckpoint.getPendingToolCallId();
                if ("TOOL_APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision())||"TOOL_DENY".equalsIgnoreCase(requestDTO.getCheckpointDecision())) {
                    if(StringUtils.isBlank(pendingToolCallId)||!pendingToolCallId.equals(requestDTO.getToolConfirmationCallId()))throw new AppException("TOOL_CONFIRMATION_MISMATCH","Tool 审批请求已失效，请刷新会话");
                    boolean expected="TOOL_APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision());
                    if(requestDTO.getToolConfirmed()==null||requestDTO.getToolConfirmed()!=expected)throw new AppException("TOOL_CONFIRMATION_INVALID","Tool 审批决策与 confirmed 字段不一致");
                }
                checkpoint = workflowCheckpointService.resume(requestDTO.getCheckpointId(),
                        requestDTO.getCheckpointRevision() == null ? -1 : requestDTO.getCheckpointRevision(),
                        requestDTO.getCheckpointDecision());
                String decision = requestDTO.getCheckpointDecision();
                com.alibaba.fastjson.JSONObject approval = JSON.parseObject(checkpoint.getApprovalJson());
                String brief = approval == null ? "" : approval.getString("rewrittenPrompt");
                if ("REVISE".equalsIgnoreCase(decision)) {
                    if ("300000".equals(requestDTO.getAgentId())) entryAgentName="agent_analyst";
                    effectiveMessage = "请修改最近的审核方案，不要开始绘图。修改意见：" + requestDTO.getMessage();
                } else if ("APPROVE".equalsIgnoreCase(decision)) {
                    if ("300000".equals(requestDTO.getAgentId())) entryAgentName="agent_drawer";
                    if (StringUtils.isBlank(brief)) throw new AppException("CHECKPOINT_APPROVAL_MISSING", "Checkpoint 不包含可批准的审核方案");
                    effectiveMessage = "[APPROVED_DRAWING_BRIEF]\n" + brief;
                } else {
                    effectiveMessage = StringUtils.isNotBlank(requestDTO.getMessage())
                            ? requestDTO.getMessage()
                            : "继续完成此前暂停的工作流。原始需求如下：\n" + checkpoint.getOriginalPrompt();
                }
            } else {
                checkpoint = workflowCheckpointService.start(requestDTO.getAgentId(), requestDTO.getUserId(), finalSessionId, requestDTO.getMessage());
                if ("300000".equals(requestDTO.getAgentId())) entryAgentName="agent_analyst";
            }
            final String finalMessage = effectiveMessage;
            final String finalEntryAgentName = entryAgentName;
            final String messageIdempotencyPrefix = StringUtils.defaultIfBlank(requestDTO.getIdempotencyKey(), checkpoint.getCheckpointId()+":"+checkpoint.getRevision());
            final java.util.UUID conversationId = StringUtils.isBlank(requestDTO.getConversationId()) ? null : java.util.UUID.fromString(requestDTO.getConversationId());
            String persistedUserMessage = requestDTO.getMessage();
            if (StringUtils.isBlank(persistedUserMessage) && "APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision())) persistedUserMessage = "确认并开始绘图";
            if (StringUtils.isBlank(persistedUserMessage) && "CONTINUE".equalsIgnoreCase(requestDTO.getCheckpointDecision())) persistedUserMessage = "继续执行此前暂停的任务";
            if (StringUtils.isBlank(persistedUserMessage) && "TOOL_APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision())) persistedUserMessage = "批准执行高风险工具";
            if (StringUtils.isBlank(persistedUserMessage) && "TOOL_DENY".equalsIgnoreCase(requestDTO.getCheckpointDecision())) persistedUserMessage = "拒绝执行高风险工具";
            if (conversationId != null && StringUtils.isNotBlank(persistedUserMessage)) {
                conversationRepository.append(requestDTO.getUserId(), conversationId, "user", "TEXT", persistedUserMessage, null, null, messageIdempotencyPrefix+":user");
                conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, "RUNNING", null);
            }

            // 保存用户自定义配置
            CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                    .baseUrl(requestDTO.getCustomBaseUrl())
                    .apiKey(requestDTO.getCustomApiKey())
                    .completionsPath(requestDTO.getCustomCompletionsPath())
                    .model(requestDTO.getCustomModel())
                    .customModelSelected(StringUtils.isNotBlank(requestDTO.getCustomModel()))
                    .build();
            CustomApiConfigManager.setConfig(finalSessionId, config);

            // Accumulate partial text per author, detect complete JSON lines to flush incrementally
            final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> authorBuffers = new java.util.concurrent.ConcurrentHashMap<>();
            // 某些 Provider 在 partial token 之后还会发送一次完整 final 文本。
            // 单独记录已收到的 partial，final 只保留尚未发送的后缀，避免前端重复拼接。
            final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> authorPartialTexts = new java.util.concurrent.ConcurrentHashMap<>();
            // Sequential agents may forward the exact same structured payload. Only expose it once to the client,
            // otherwise an approval can advance the checkpoint revision repeatedly.
            final java.util.Set<String> emittedStructuredLines = java.util.concurrent.ConcurrentHashMap.newKeySet();
            final java.util.concurrent.ConcurrentHashMap<String, Long> toolStartedAt = new java.util.concurrent.ConcurrentHashMap<>();
            final java.util.concurrent.atomic.AtomicReference<String> monitorInvocationId = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicBoolean invocationLinked = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean clientConnected = new java.util.concurrent.atomic.AtomicBoolean(true);
            final java.util.concurrent.atomic.AtomicBoolean workflowFinished = new java.util.concurrent.atomic.AtomicBoolean(false);
            final StringBuilder persistedAssistantText = new StringBuilder();

            com.alibaba.fastjson.JSONObject checkpointEnvelope = new com.alibaba.fastjson.JSONObject();
            checkpointEnvelope.put("phase", "thinking");
            com.alibaba.fastjson.JSONObject checkpointChunk = new com.alibaba.fastjson.JSONObject();
            checkpointChunk.put("type", "checkpoint");
            checkpointChunk.put("checkpointId", checkpoint.getCheckpointId());
            checkpointChunk.put("revision", checkpoint.getRevision());
            checkpointChunk.put("status", checkpoint.getStatus());
            checkpointEnvelope.put("chunk", checkpointChunk);
            emitter.send(checkpointEnvelope.toJSONString() + "\n");

            boolean toolDecision="TOOL_APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision())||"TOOL_DENY".equalsIgnoreCase(requestDTO.getCheckpointDecision());
            io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> eventStream = toolDecision
                    ?chatService.handleToolConfirmationStream(requestDTO.getAgentId(),requestDTO.getUserId(),finalSessionId,requestDTO.getToolConfirmationCallId(),Boolean.TRUE.equals(requestDTO.getToolConfirmed()),requestDTO.getToolConfirmationPayload(),requestDTO.getIdempotencyKey())
                    :chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(), finalSessionId, finalMessage,finalEntryAgentName,requestDTO.getIdempotencyKey());
            io.reactivex.rxjava3.disposables.Disposable disposable = eventStream
                    .subscribe(
                            event -> {
                                try {
                                    if (monitorInvocationId.compareAndSet(null, event.invocationId()) && event.invocationId()!=null) {
                                        runtimeObservationRepository.bindInvocationRequest(event.invocationId(), requestDTO.getIdempotencyKey());
                                    }
                                    if(conversationId!=null&&event.invocationId()!=null&&invocationLinked.compareAndSet(false,true))conversationRepository.updateStatus(requestDTO.getUserId(),conversationId,"RUNNING",event.invocationId());
                                    // Determine phase from author
                                    String author = event.author();
                                    String phase;
                                    switch (author != null ? author : "unknown") {
                                        case "agent_analyst":
                                        case "agent_ppt_analyst":
                                            phase = "analyzing";
                                            break;
                                        case "agent_drawer":
                                            phase = "drawing";
                                            break;
                                        case "agent_ppt_generator":
                                            phase = "generating";
                                            break;
                                        case "agent_reviewer":
                                        case "agent_ppt_reviewer":
                                            phase = "reviewing";
                                            break;
                                        default:
                                            phase = "thinking";
                                    }

                                    // Tool 生命周期单独发送，避免把参数/结果混入 Markdown，同时让前端显示准确耗时。
                                    if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) {
                                        for (com.google.genai.types.FunctionCall call : event.functionCalls()) {
                                            String callId=call.id().orElseGet(()->java.util.UUID.randomUUID().toString());long started=System.currentTimeMillis();toolStartedAt.put(callId,started);
                                            if("adk_request_confirmation".equals(call.name().orElse(""))){
                                                Map<String,Object> safeConfirmation=lightweightMonitorService.redactToolResult(call.args().orElse(Map.of()));
                                                String confirmationJson=JSON.toJSONString(safeConfirmation);
                                                WorkflowCheckpointEntity waiting=workflowCheckpointService.waitForToolApproval(checkpoint.getCheckpointId(),event.invocationId(),callId,confirmationJson);
                                                runtimeObservationRepository.workflowState(waiting.getSessionId(),waiting.getCheckpointId(),waiting.getStatus());
                                                com.alibaba.fastjson.JSONObject envelope=new com.alibaba.fastjson.JSONObject();envelope.put("phase","thinking");
                                                com.alibaba.fastjson.JSONObject chunk=new com.alibaba.fastjson.JSONObject();chunk.put("type","tool_approval");chunk.put("callId",callId);chunk.put("checkpointId",waiting.getCheckpointId());chunk.put("revision",waiting.getRevision());chunk.put("details",safeConfirmation);envelope.put("chunk",chunk);emitter.send(envelope.toJSONString()+"\n");
                                                if(conversationId!=null)conversationRepository.append(requestDTO.getUserId(),conversationId,"assistant","TOOL_APPROVAL","高风险工具等待批准",chunk.toJSONString(),event.invocationId(),messageIdempotencyPrefix+":tool-approval:"+callId);
                                                continue;
                                            }
                                            sendToolEvent(emitter,phase,callId,call.name().orElse("unknown-tool"),"RUNNING",started,null);
                                        }
                                        for (com.google.genai.types.FunctionResponse response : event.functionResponses()) {
                                            String callId=response.id().orElseGet(()->java.util.UUID.randomUUID().toString());long ended=System.currentTimeMillis();long started=toolStartedAt.getOrDefault(callId,ended);
                                            boolean failed=response.response().map(map->map.containsKey("error")||Boolean.FALSE.equals(map.get("success"))).orElse(false);
                                            sendToolEvent(emitter,phase,callId,response.name().orElse("unknown-tool"),failed?"FAILED":"SUCCESS",started,ended-started);toolStartedAt.remove(callId);
                                        }
                                        return;
                                    }

                                    String content = event.stringifyContent();
                                    if (content == null || content.isEmpty()) {
                                        return;
                                    }
                                    synchronized (persistedAssistantText) { persistedAssistantText.append(content); }

                                    boolean isPartial = event.partial().orElse(false);
                                    StringBuilder partialText = authorPartialTexts.computeIfAbsent(author == null ? "unknown" : author, k -> new StringBuilder());
                                    if (isPartial) {
                                        partialText.append(content);
                                    } else if (partialText.length() > 0) {
                                        String streamed = partialText.toString();
                                        if (content.startsWith(streamed)) {
                                            content = content.substring(streamed.length());
                                        } else if (streamed.endsWith(content)) {
                                            content = "";
                                        }
                                        partialText.setLength(0);
                                    }

                                    // For PPT generation and reviewing, stream the content directly as ppt_raw
                                    // This prevents buffering huge JSON strings and causing frontend timeouts
                                    if ("agent_ppt_generator".equals(author) || "agent_ppt_reviewer".equals(author)) {
                                        try {
                                            com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
                                            wrapper.put("phase", phase);
                                            com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
                                            chunk.put("type", "ppt_raw");
                                            chunk.put("raw", content);
                                            wrapper.put("chunk", chunk);
                                            emitter.send(wrapper.toJSONString() + "\n");
                                        } catch (Exception ignored) {}
                                        return;
                                    }

                                    StringBuilder buffer = authorBuffers.computeIfAbsent(author, k -> new StringBuilder());
                                    
                                    String currentActiveLine;
                                    int lastNewline = buffer.lastIndexOf("\n");
                                    if (lastNewline >= 0) {
                                        currentActiveLine = buffer.substring(lastNewline + 1);
                                    } else {
                                        currentActiveLine = buffer.toString();
                                    }
                                    currentActiveLine = currentActiveLine.trim();
                                    
                                    boolean isLikelyJson = currentActiveLine.startsWith("{") || (currentActiveLine.isEmpty() && content.trim().startsWith("{"));

                                    if (!isLikelyJson) {
                                        // 向前端发送实时的 token 数据，仅对非JSON数据发送，避免前端出现乱码
                                        try {
                                            com.alibaba.fastjson.JSONObject tokenMsg = new com.alibaba.fastjson.JSONObject();
                                            tokenMsg.put("phase", phase);
                                            com.alibaba.fastjson.JSONObject tokenChunk = new com.alibaba.fastjson.JSONObject();
                                            tokenChunk.put("type", "token");
                                            tokenChunk.put("content", content);
                                            tokenMsg.put("chunk", tokenChunk);
                                            emitter.send(tokenMsg.toJSONString() + "\n");
                                        } catch (Exception ignored) {}
                                    }

                                    buffer.append(content);
                                    String accumulated = buffer.toString();
                                    if (isPartial) {
                                        if (accumulated.contains("\n")) {
                                            String[] lines = accumulated.split("\n", -1);
                                            buffer.setLength(0);
                                            buffer.append(lines[lines.length - 1]);
                                            for (int i = 0; i < lines.length - 1; i++) {
                                                processAndSendUniqueLine(emitter, phase, lines[i], checkpoint,
                                                        monitorInvocationId.get(), emittedStructuredLines);
                                            }
                                        }
                                    } else {
                                        // With StreamingMode.NONE every content event is already complete. Flush it
                                        // exactly once instead of retaining and processing the last line twice.
                                        buffer.setLength(0);
                                        for (String line : accumulated.split("\n")) {
                                            processAndSendUniqueLine(emitter, phase, line, checkpoint,
                                                    monitorInvocationId.get(), emittedStructuredLines);
                                        }
                                    }
                                } catch (Exception e) {
                                    // 输出流与 Agent 执行解耦：浏览器刷新、代理断流只停止推送，
                                    // 不取消 ADK/Tool；最终状态仍由订阅完成回调落盘。
                                    if (clientConnected.compareAndSet(true, false)) {
                                        log.warn("客户端流已断开，工作流将在后台继续 sessionId:{} reason:{}", finalSessionId, e.getMessage());
                                    }
                                }
                            },
                            error -> {
                                sessionExecutionGuard.release(executionLease.getAndSet(null));
                                workflowFinished.set(true);
                                CustomApiConfigManager.clearConfig(finalSessionId);
                                String invocationId = monitorInvocationId.get();
                                if (invocationId != null) lightweightMonitorService.runCompleted(invocationId, false, error.getMessage());
                                workflowCheckpointService.finish(checkpoint.getCheckpointId(), false, error.getMessage());
                                WorkflowCheckpointEntity failedCheckpoint=workflowCheckpointService.get(checkpoint.getCheckpointId());
                                runtimeObservationRepository.workflowState(failedCheckpoint.getSessionId(),failedCheckpoint.getCheckpointId(),failedCheckpoint.getStatus());
                                if(streamIdempotencyAcquired.get())idempotencyService.fail(requestDTO.getUserId(),streamIdempotencyScope.get(),requestDTO.getIdempotencyKey(),error);
                                if (conversationId != null) conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, "FAILED", invocationId);
                                if (error instanceof IllegalStateException && error.getMessage() != null && error.getMessage().contains("ResponseBodyEmitter has already completed")) {
                                    log.warn("流式对话已结束(客户端断开或主动完成)");
                                    return;
                                }
                                if (error instanceof java.io.IOException || (error.getMessage() != null && error.getMessage().contains("Broken pipe"))) {
                                    log.warn("流式对话连接断开: {}", error.getMessage());
                                    return;
                                }
                                // If the cause is one of the above
                                if (error.getCause() instanceof IllegalStateException && error.getCause().getMessage() != null && error.getCause().getMessage().contains("ResponseBodyEmitter has already completed")) {
                                    log.warn("流式对话已结束(客户端断开或主动完成)");
                                    return;
                                }
                                if (error.getCause() instanceof java.io.IOException) {
                                    log.warn("流式对话连接断开: {}", error.getCause().getMessage());
                                    return;
                                }
                                log.error("流式对话异常", error);
                                try {
                                    com.alibaba.fastjson.JSONObject errMsg = new com.alibaba.fastjson.JSONObject();
                                    errMsg.put("phase", "error");
                                    com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
                                    chunk.put("type", "error");
                                    chunk.put("content", "对话异常，请重试");
                                    errMsg.put("chunk", chunk);
                                    emitter.send(errMsg.toJSONString() + "\n");
                                } catch (Exception ignored) {}
                                emitter.completeWithError(error);
                            },
                            () -> {
                                sessionExecutionGuard.release(executionLease.getAndSet(null));
                                workflowFinished.set(true);
                                String invocationId = monitorInvocationId.get();
                                if (invocationId != null) lightweightMonitorService.runCompleted(invocationId, true, "");
                                // Flush any remaining buffers
                                for (StringBuilder buf : authorBuffers.values()) {
                                    String remaining = buf.toString().trim();
                                    if (!remaining.isEmpty()) {
                                        try {
                                            processAndSendLine(emitter, "done", remaining, checkpoint, monitorInvocationId.get());
                                        } catch (Exception ignored) {}
                                    }
                                }
                                try {
                                    com.alibaba.fastjson.JSONObject doneMsg = new com.alibaba.fastjson.JSONObject();
                                    doneMsg.put("phase", "done");
                                    com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
                                    chunk.put("type", "done");
                                    doneMsg.put("chunk", chunk);
                                    emitter.send(doneMsg.toJSONString() + "\n");
                                } catch (Exception ignored) {}
                                workflowCheckpointService.finish(checkpoint.getCheckpointId(), true, "");
                                try {
                                  WorkflowCheckpointEntity persistedCheckpoint=workflowCheckpointService.get(checkpoint.getCheckpointId());
                                  runtimeObservationRepository.workflowState(persistedCheckpoint.getSessionId(),persistedCheckpoint.getCheckpointId(),persistedCheckpoint.getStatus());
                                  if (conversationId != null) {
                                    String rawAssistant;
                                    synchronized (persistedAssistantText) { rawAssistant = persistedAssistantText.toString(); }
                                    String drawioXml=extractStructuredContent(rawAssistant,"drawio_done","content");
                                    if(StringUtils.isBlank(drawioXml))drawioXml=extractStructuredContent(rawAssistant,"drawio","content");
                                    String approvalJson=persistedCheckpoint.getApprovalJson();
                                    if(WorkflowCheckpointService.WAITING_APPROVAL.equals(persistedCheckpoint.getStatus())&&StringUtils.isNotBlank(approvalJson)){
                                        com.alibaba.fastjson.JSONObject approval=JSON.parseObject(approvalJson);
                                        String approvalText=approval==null?"等待审核":StringUtils.defaultIfBlank(approval.getString("rewrittenPrompt"),"等待审核");
                                        conversationRepository.append(requestDTO.getUserId(),conversationId,"assistant","APPROVAL",approvalText,approvalJson,invocationId,messageIdempotencyPrefix+":approval");
                                    }
                                    if(StringUtils.isNotBlank(drawioXml)){
                                        com.alibaba.fastjson.JSONObject payload=new com.alibaba.fastjson.JSONObject();payload.put("xml",drawioXml);payload.put("checkpointId",checkpoint.getCheckpointId());
                                        String approvedBrief="";if(StringUtils.isNotBlank(approvalJson)){com.alibaba.fastjson.JSONObject approved=JSON.parseObject(approvalJson);if(approved!=null)approvedBrief=StringUtils.defaultString(approved.getString("rewrittenPrompt"));}
                                        String completedReply=(StringUtils.isBlank(approvedBrief)?"已按审核方案完成绘图。":"已按审核方案完成绘图：\n\n"+approvedBrief)+"\n\n图表已生成完成，可在画布中继续编辑或导出。";
                                        conversationRepository.append(requestDTO.getUserId(),conversationId,"assistant","DRAWIO",completedReply,payload.toJSONString(),invocationId,messageIdempotencyPrefix+":drawio-message");
                                        artifactRepository.save(conversationId,invocationId,"DRAWIO","Draw.io 图表","application/vnd.jgraph.mxfile",drawioXml,"{}",messageIdempotencyPrefix+":drawio-artifact");
                                    }else{
                                        String assistant=cleanPersistedAssistant(rawAssistant);
                                        if(StringUtils.isNotBlank(assistant)){
                                            conversationRepository.append(requestDTO.getUserId(),conversationId,"assistant","TEXT",assistant,null,invocationId,messageIdempotencyPrefix+":assistant-message");
                                            artifactRepository.save(conversationId,invocationId,assistant.contains("\"type\":\"ppt\"")?"PPT":"AGENT_OUTPUT","Agent 输出",assistant.contains("\"type\":\"ppt\"")?"application/json":"text/markdown",assistant,"{}",messageIdempotencyPrefix+":agent-artifact");
                                        }
                                    }
                                    String finalStatus=WorkflowCheckpointService.WAITING_APPROVAL.equals(persistedCheckpoint.getStatus())?"WAITING_APPROVAL":WorkflowCheckpointService.WAITING_TOOL_APPROVAL.equals(persistedCheckpoint.getStatus())?"WAITING_TOOL_APPROVAL":"COMPLETED";
                                    conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, finalStatus, invocationId);
                                  }
                                  if(streamIdempotencyAcquired.get())idempotencyService.complete(requestDTO.getUserId(),streamIdempotencyScope.get(),requestDTO.getIdempotencyKey(),StringUtils.defaultIfBlank(invocationId,persistedCheckpoint.getCheckpointId()),JSON.toJSONString(java.util.Map.of("checkpointId",persistedCheckpoint.getCheckpointId(),"revision",persistedCheckpoint.getRevision(),"status",persistedCheckpoint.getStatus(),"invocationId",StringUtils.defaultString(invocationId))));
                                } catch (Exception persistenceError) {
                                    log.error("Agent 已完成但会话产物持久化失败 sessionId:{} invocationId:{}", finalSessionId, invocationId, persistenceError);
                                    if (conversationId != null) conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, "FAILED", invocationId);
                                } finally {
                                    CustomApiConfigManager.clearConfig(finalSessionId);
                                    emitter.complete();
                                }
                            }
                    );

            emitter.onCompletion(() -> {
                log.info("流式对话 emitter.onCompletion sessionId:{}", finalSessionId);
                clientConnected.set(false);
                if (!workflowFinished.get()) log.info("客户端连接先于工作流结束关闭，后台继续执行 sessionId:{}", finalSessionId);
            });
            emitter.onTimeout(() -> {
                log.info("流式对话 emitter.onTimeout sessionId:{}", finalSessionId);
                clientConnected.set(false);
                emitter.complete();
            });
            emitter.onError(e -> {
                log.info("流式对话 emitter.onError sessionId:{}", finalSessionId);
                clientConnected.set(false);
            });
        } catch (Exception e) {
            CustomApiConfigManager.clearConfig(requestDTO.getSessionId());
            if(streamIdempotencyAcquired.get())idempotencyService.fail(requestDTO.getUserId(),streamIdempotencyScope.get(),requestDTO.getIdempotencyKey(),e);
            sessionExecutionGuard.release(executionLease.getAndSet(null));
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * Process a single line: try to parse as drawio/PPT JSON, otherwise send as status text.
     */
    private boolean processAndSendLine(ResponseBodyEmitter emitter, String phase, String line,
                                    WorkflowCheckpointEntity checkpoint, String invocationId) throws Exception {
        try {
            com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(line);
            if (json != null && json.containsKey("type")) {
                String type = json.getString("type");
                if ("approval".equals(type)) {
                    // Be tolerant of a frequent model spelling variant, but always expose the canonical contract.
                    if (!json.containsKey("rewrittenPrompt")) {
                        String rewritten = json.getString("rewedPrompt");
                        if (org.apache.commons.lang3.StringUtils.isBlank(rewritten)) rewritten = json.getString("rewritePrompt");
                        if (org.apache.commons.lang3.StringUtils.isNotBlank(rewritten)) json.put("rewrittenPrompt", rewritten);
                    }
                    json.remove("rewedPrompt");
                    json.remove("rewritePrompt");
                    WorkflowCheckpointEntity saved = workflowCheckpointService.approval(checkpoint.getCheckpointId(), invocationId, json.toJSONString());
                    runtimeObservationRepository.workflowState(saved.getSessionId(),saved.getCheckpointId(),saved.getStatus());
                    json.put("checkpointId", saved.getCheckpointId());
                    json.put("revision", saved.getRevision());
                    json.put("checkpointStatus", saved.getStatus());
                }
                if ("drawio_node".equals(type) || "drawio_edge".equals(type) || "drawio_done".equals(type)
                        || "user".equals(type) || "approval".equals(type) || "drawio".equals(type)) {
                    // Structured drawio output - pass through with phase info
                    com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
                    wrapper.put("phase", phase);
                    wrapper.put("chunk", json);
                    emitter.send(wrapper.toJSONString() + "\n");
                    
                    // 模拟人类画图操作停顿，让前端有足够的时间进行渲染
                    if ("drawio_node".equals(type) || "drawio_edge".equals(type)) {
                        Thread.sleep(250);
                    }
                    
                    return "approval".equals(type);
                }
                
                // PPT output: send entire JSON as ppt_raw for frontend incremental parsing
                if ("ppt".equals(type) || json.containsKey("slides")) {
                    com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
                    wrapper.put("phase", phase);
                    com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
                    chunk.put("type", "ppt_raw");
                    chunk.put("raw", line);
                    wrapper.put("chunk", chunk);
                    emitter.send(wrapper.toJSONString() + "\n");
                    return false;
                }
            }
        } catch (Exception parseEx) {
            // Not a JSON line, fall through to treat as text
        }

        // Check if this line looks like it might be part of a PPT JSON (contains slide-like structures)
        // Send as ppt_raw for incremental parsing on the frontend
        if (line.contains("\"slides\"") || line.contains("\"slideIndex\"") || line.contains("\"elements\"")) {
            com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
            wrapper.put("phase", phase);
            com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
            chunk.put("type", "ppt_raw");
            chunk.put("raw", line);
            wrapper.put("chunk", chunk);
            emitter.send(wrapper.toJSONString() + "\n");
            return false;
        }

        // Non-JSON content or unrecognized JSON - send as phase status
        com.alibaba.fastjson.JSONObject statusMsg = new com.alibaba.fastjson.JSONObject();
        com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
        chunk.put("type", "status");
        chunk.put("content", line);
        statusMsg.put("phase", phase);
        statusMsg.put("chunk", chunk);
        emitter.send(statusMsg.toJSONString() + "\n");
        return false;
    }

    @GetMapping("monitor/workflows/{taskId}")
    public Response<Map<String,Object>> workflowDetail(@PathVariable String taskId){return Response.<Map<String,Object>>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(runtimeObservationRepository.workflowDetail(AuthenticatedUserContext.current(),taskId)).build();}

    @GetMapping("monitor/sessions/{sessionId}/invocations")
    public Response<List<Map<String,Object>>> monitorSession(@PathVariable String sessionId){
        return Response.<List<Map<String,Object>>>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(runtimeObservationRepository.listBySession(AuthenticatedUserContext.current(),sessionId)).build();
    }

    private boolean processAndSendUniqueLine(ResponseBodyEmitter emitter, String phase, String rawLine,
                                          WorkflowCheckpointEntity checkpoint, String invocationId,
                                          java.util.Set<String> emittedStructuredLines) throws Exception {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty()) return false;
        boolean structured = line.startsWith("{") && line.endsWith("}");
        if (structured && !emittedStructuredLines.add(line)) return false;
        return processAndSendLine(emitter, phase, line, checkpoint, invocationId);
    }

    private void sendToolEvent(ResponseBodyEmitter emitter,String phase,String callId,String name,String status,long startedAt,Long durationMs)throws Exception{
        com.alibaba.fastjson.JSONObject envelope=new com.alibaba.fastjson.JSONObject();envelope.put("phase",phase);
        com.alibaba.fastjson.JSONObject chunk=new com.alibaba.fastjson.JSONObject();chunk.put("type","tool");chunk.put("callId",callId);chunk.put("name",name);chunk.put("status",status);chunk.put("startedAt",startedAt);if(durationMs!=null)chunk.put("durationMs",durationMs);
        envelope.put("chunk",chunk);emitter.send(envelope.toJSONString()+"\n");
    }

    private String extractStructuredContent(String raw,String expectedType,String field){
        if(StringUtils.isBlank(raw))return "";String found="";
        for(String source:raw.split("\\R")){int start=source.indexOf("{\"type\"");if(start<0)continue;try{com.alibaba.fastjson.JSONObject json=JSON.parseObject(source.substring(start).trim());if(expectedType.equals(json.getString("type")))found=StringUtils.defaultString(json.getString(field));}catch(Exception ignored){}}
        return found;
    }

    private String cleanPersistedAssistant(String raw){
        if(StringUtils.isBlank(raw))return "";StringBuilder clean=new StringBuilder();
        for(String source:raw.split("\\R")){String line=source.trim();if(line.isBlank()||line.startsWith("[APPROVED_DRAWING_BRIEF]"))continue;int json=line.indexOf("{\"type\"");if(json==0)continue;if(json>0)line=line.substring(0,json).trim();if(!line.isBlank())clean.append(line).append('\n');}
        return clean.toString().trim();
    }

}
