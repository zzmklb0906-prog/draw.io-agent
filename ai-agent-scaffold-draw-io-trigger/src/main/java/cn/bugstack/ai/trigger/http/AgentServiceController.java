package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowCheckpointEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatStreamEventEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatStreamRunEntity;
import cn.bugstack.ai.domain.agent.adapter.repository.IChatStreamRunRepository;
import cn.bugstack.ai.domain.agent.service.workflow.WorkflowCheckpointService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import cn.bugstack.ai.domain.agent.service.chat.CustomApiConfigManager;
import org.apache.commons.lang3.StringUtils;
import cn.bugstack.ai.trigger.http.auth.AuthenticatedUserContext;
import cn.bugstack.ai.domain.conversation.adapter.IConversationRepository;
import cn.bugstack.ai.domain.conversation.model.ConversationView;
import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeObservationRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IDynamicSubagentRepository;
import cn.bugstack.ai.domain.artifact.adapter.IArtifactRepository;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import cn.bugstack.ai.domain.eval.service.AgentEvalService;
import cn.bugstack.ai.domain.idempotency.service.IdempotencyService;

/**
 * 智能体服务接口控制器
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
    @Resource
    private IChatStreamRunRepository chatStreamRunRepository;

    @Value("${ai.agent.chat.stream-timeout-ms:1200000}")
    private long streamTimeoutMs;

    @Value("${ai.agent.chat.execution-timeout-ms:900000}")
    private long executionTimeoutMs;

    private final ScheduledExecutorService scheduledExecutor =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "sse-subscriber-poll");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService workflowExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "chat-workflow-bg");
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    public void destroy() {
        scheduledExecutor.shutdownNow();
        workflowExecutor.shutdownNow();
    }

    @GetMapping("workflows/{checkpointId}")
    public Response<WorkflowCheckpointEntity> workflowCheckpoint(@PathVariable String checkpointId) {
        WorkflowCheckpointEntity checkpoint = workflowCheckpointService.get(checkpointId);
        AuthenticatedUserContext.require(checkpoint.getUserId());
        return Response.<WorkflowCheckpointEntity>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(checkpoint).build();
    }

    @PostMapping("workflows/{checkpointId}/pause")
    public Response<WorkflowCheckpointEntity> pauseWorkflow(@PathVariable String checkpointId) {
        WorkflowCheckpointEntity existing = workflowCheckpointService.get(checkpointId);
        AuthenticatedUserContext.require(existing.getUserId());
        WorkflowCheckpointEntity saved = workflowCheckpointService.pause(checkpointId);
        runtimeObservationRepository.workflowState(saved.getSessionId(), saved.getCheckpointId(), saved.getStatus());
        return workflowResponse(saved);
    }

    @PostMapping("workflows/{checkpointId}/cancel")
    public Response<WorkflowCheckpointEntity> cancelWorkflow(@PathVariable String checkpointId) {
        WorkflowCheckpointEntity existing = workflowCheckpointService.get(checkpointId);
        AuthenticatedUserContext.require(existing.getUserId());
        WorkflowCheckpointEntity saved = workflowCheckpointService.cancel(checkpointId);
        runtimeObservationRepository.workflowState(saved.getSessionId(), saved.getCheckpointId(), saved.getStatus());
        return workflowResponse(saved);
    }

    private Response<WorkflowCheckpointEntity> workflowResponse(WorkflowCheckpointEntity checkpoint) {
        return Response.<WorkflowCheckpointEntity>builder().code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo()).data(checkpoint).build();
    }

    @GetMapping("monitor/summary")
    public Response<Map<String, Object>> monitorSummary(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Integer hours) {
        int windowHours = (hours != null && (hours == 1 || hours == 24 || hours == 168)) ? hours : 24;
        Map<String, Object> live = lightweightMonitorService.summary(AuthenticatedUserContext.current());
        Map<String, Object> persisted = runtimeObservationRepository.summary(AuthenticatedUserContext.current(), sessionId, windowHours);
        Map<String, Object> data = new java.util.LinkedHashMap<>(persisted);
        data.put("registeredTools", live.getOrDefault("registeredTools", List.of()));
        data.put("registeredCapabilities", capabilityRegistryService.size());
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private static final Set<String> TERMINAL_INVOCATION_STATUSES = Set.of("SUCCESS", "ERROR", "FAILED", "INTERRUPTED");

    @GetMapping("monitor/invocations")
    public Response<List<Map<String, Object>>> monitorInvocations() {
        List<Map<String, Object>> live = lightweightMonitorService.list(AuthenticatedUserContext.current());
        List<Map<String, Object>> persisted = runtimeObservationRepository.listRecent(AuthenticatedUserContext.current(), 200);
        java.util.LinkedHashMap<String, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
        if (live != null) {
            for (Map<String, Object> item : live) {
                if (item != null && item.get("invocationId") != null) {
                    merged.put(String.valueOf(item.get("invocationId")), new java.util.LinkedHashMap<>(item));
                }
            }
        }
        if (persisted != null) {
            for (Map<String, Object> item : persisted) {
                if (item == null || item.get("invocationId") == null) continue;
                String id = String.valueOf(item.get("invocationId"));
                Map<String, Object> existing = merged.get(id);
                if (existing == null) {
                    merged.put(id, new java.util.LinkedHashMap<>(item));
                } else {
                    mergeInvocation(existing, item);
                }
            }
        }
        return Response.<List<Map<String, Object>>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(new java.util.ArrayList<>(merged.values()))
                .build();
    }

    private void mergeInvocation(Map<String, Object> target, Map<String, Object> persisted) {
        Object targetTaskId = target.get("taskId");
        Object persistedTaskId = persisted.get("taskId");
        if ((targetTaskId == null || String.valueOf(targetTaskId).trim().isEmpty())
                && persistedTaskId != null && !String.valueOf(persistedTaskId).trim().isEmpty()) {
            target.put("taskId", persistedTaskId);
        }

        Object targetWorkflow = target.get("workflowName");
        Object persistedWorkflow = persisted.get("workflowName");
        boolean targetWorkflowBlank = targetWorkflow == null
                || String.valueOf(targetWorkflow).trim().isEmpty()
                || "unknown".equalsIgnoreCase(String.valueOf(targetWorkflow).trim());
        boolean persistedWorkflowValid = persistedWorkflow != null
                && !String.valueOf(persistedWorkflow).trim().isEmpty()
                && !"unknown".equalsIgnoreCase(String.valueOf(persistedWorkflow).trim());
        if (targetWorkflowBlank && persistedWorkflowValid) {
            target.put("workflowName", persistedWorkflow);
        }

        Object liveStatus = target.get("status");
        Object persistedStatus = persisted.get("status");
        String liveStatusStr = liveStatus == null ? "" : String.valueOf(liveStatus).trim().toUpperCase(Locale.ROOT);
        String persistedStatusStr = persistedStatus == null ? "" : String.valueOf(persistedStatus).trim().toUpperCase(Locale.ROOT);

        boolean liveTerminal = TERMINAL_INVOCATION_STATUSES.contains(liveStatusStr);
        boolean persistedTerminal = TERMINAL_INVOCATION_STATUSES.contains(persistedStatusStr);

        if (!liveTerminal && persistedTerminal) {
            target.put("status", persisted.get("status"));
            if (persisted.containsKey("completedAt") && persisted.get("completedAt") != null) {
                target.put("completedAt", persisted.get("completedAt"));
            }
            if (persisted.containsKey("durationMs") && persisted.get("durationMs") != null) {
                target.put("durationMs", persisted.get("durationMs"));
            }
            if ("SUCCESS".equals(persistedStatusStr)) {
                target.remove("error");
                target.remove("errorMessage");
            } else {
                if (persisted.containsKey("error") && persisted.get("error") != null) {
                    target.put("error", persisted.get("error"));
                } else if (persisted.containsKey("errorMessage") && persisted.get("errorMessage") != null) {
                    target.put("error", persisted.get("errorMessage"));
                }
            }
        }
    }

    @GetMapping("monitor/invocations/{invocationId}")
    public Response<Map<String, Object>> monitorInvocation(@PathVariable("invocationId") String invocationId) {
        try {
            Map<String, Object> live = lightweightMonitorService.detail(invocationId, AuthenticatedUserContext.current());
            Map<String, Object> persisted = runtimeObservationRepository.detail(AuthenticatedUserContext.current(), invocationId);
            Map<String, Object> structure = runtimeObservationRepository.executionStructure(AuthenticatedUserContext.current(), invocationId);
            if (!structure.isEmpty()) {
                persisted = new java.util.LinkedHashMap<>(persisted);
                persisted.putAll(structure);
            }
            if (!persisted.isEmpty()) {
                persisted = new java.util.LinkedHashMap<>(persisted);
                persisted.put("agentRuns", runtimeObservationRepository.agentRuns(AuthenticatedUserContext.current(), invocationId));
                persisted.put("waterfall", runtimeObservationRepository.waterfall(AuthenticatedUserContext.current(), invocationId));
                persisted.put("subagentTasks", dynamicSubagentRepository.tasks(AuthenticatedUserContext.current(), invocationId));
                persisted.put("capabilitySearches", runtimeObservationRepository.capabilitySearches(AuthenticatedUserContext.current(), invocationId));
                persisted.put("capabilityExecutions", runtimeObservationRepository.capabilityExecutions(AuthenticatedUserContext.current(), invocationId));
                persisted.put("evaluations", agentEvalService.forInvocation(AuthenticatedUserContext.current(), invocationId));
            }
            Map<String, Object> data;
            if (live.isEmpty()) data = persisted;
            else {
                data = new java.util.LinkedHashMap<>(persisted);
                data.putAll(live);
                if (persisted.get("models") instanceof List<?> models && !models.isEmpty()) data.put("models", models);
                if (persisted.get("tools") instanceof List<?> tools && !tools.isEmpty()) data.put("tools", tools);
                if (persisted.get("events") instanceof List<?> events && !events.isEmpty()) data.put("events", events);
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
        boolean idempotencyAcquired = false;
        try {
            AuthenticatedUserContext.require(requestDTO.getUserId());
            String requestFingerprint = requestDTO.getAgentId() + "|" + requestDTO.getUserId();
            IdempotencyService.Claim claim = idempotencyService.begin(requestDTO.getUserId(), "CREATE_SESSION", requestDTO.getIdempotencyKey(), requestFingerprint);
            idempotencyAcquired = claim.acquired();
            if (claim.replay()) {
                CreateSessionResponseDTO replay = JSON.parseObject(claim.record().responseJson(), CreateSessionResponseDTO.class);
                return Response.<CreateSessionResponseDTO>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(replay).build();
            }
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);
            responseDTO.setConversationId(conversationRepository.create(requestDTO.getUserId(), requestDTO.getAgentId(), sessionId, "新会话").id().toString());
            idempotencyService.complete(requestDTO.getUserId(), "CREATE_SESSION", requestDTO.getIdempotencyKey(), responseDTO.getConversationId(), JSON.toJSONString(responseDTO));

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            if (idempotencyAcquired) idempotencyService.fail(requestDTO.getUserId(), "CREATE_SESSION", requestDTO.getIdempotencyKey(), e);
            log.error("创建会话异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            if (idempotencyAcquired) idempotencyService.fail(requestDTO.getUserId(), "CREATE_SESSION", requestDTO.getIdempotencyKey(), e);
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
                String result = messages.stream().reduce((first, second) -> second).orElse("");
                ChatResponseDTO parsed = JSON.parseObject(result, ChatResponseDTO.class);
                if (null != parsed) {
                    responseDTO = parsed;
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
            log.error("智能体对话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        } finally {
            CustomApiConfigManager.clearConfig(requestDTO.getSessionId());
        }
    }

    @PostMapping("chat_stream")
    @Override
    public Response<ChatStreamRunResponseDTO> createChatStreamRun(@RequestBody ChatRequestDTO requestDTO) {
        final AtomicReference<SessionExecutionGuard.Lease> executionLease = new AtomicReference<>();
        try {
            AuthenticatedUserContext.require(requestDTO.getUserId());

            // 1. Idempotency Check: if run exists for (userId, idempotencyKey), return it directly
            if (StringUtils.isNotBlank(requestDTO.getIdempotencyKey())) {
                Optional<ChatStreamRunEntity> existingRun = chatStreamRunRepository.findByIdempotencyKey(requestDTO.getUserId(), requestDTO.getIdempotencyKey());
                if (existingRun.isPresent()) {
                    log.info("Chat stream run idempotent replay runId:{} idempotencyKey:{}", existingRun.get().getRunId(), requestDTO.getIdempotencyKey());
                    return Response.<ChatStreamRunResponseDTO>builder()
                            .code(ResponseCode.SUCCESS.getCode())
                            .info(ResponseCode.SUCCESS.getInfo())
                            .data(toDTO(existingRun.get()))
                            .build();
                }
            }

            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
                requestDTO.setSessionId(sessionId);
            }
            final String finalSessionId = sessionId;

            // 2. Acquire session execution lease
            SessionExecutionGuard.Lease lease;
            try {
                lease = sessionExecutionGuard.acquire(requestDTO.getUserId(), finalSessionId);
                executionLease.set(lease);
            } catch (AppException e) {
                if ("SESSION_BUSY".equals(e.getCode()) && StringUtils.isNotBlank(requestDTO.getIdempotencyKey())) {
                    Optional<ChatStreamRunEntity> existingRun = chatStreamRunRepository.findByIdempotencyKey(requestDTO.getUserId(), requestDTO.getIdempotencyKey());
                    if (existingRun.isPresent()) {
                        log.info("Chat stream run idempotent replay on busy lease runId:{} idempotencyKey:{}", existingRun.get().getRunId(), requestDTO.getIdempotencyKey());
                        return Response.<ChatStreamRunResponseDTO>builder()
                                .code(ResponseCode.SUCCESS.getCode())
                                .info(ResponseCode.SUCCESS.getInfo())
                                .data(toDTO(existingRun.get()))
                                .build();
                    }
                }
                throw e;
            }

            // 3. Handle Checkpoint & Decision
            final WorkflowCheckpointEntity checkpoint;
            String effectiveMessage = requestDTO.getMessage();
            String entryAgentName = null;
            String checkpointId = requestDTO.getCheckpointId();
            Long checkpointRevision = requestDTO.getCheckpointRevision();

            // 安全兜底：如果前端传递了恢复决策(如 APPROVE/REVISE)但未携带 checkpointId，自动从当前会话关联中恢复
            if (StringUtils.isBlank(checkpointId) && StringUtils.isNotBlank(requestDTO.getCheckpointDecision()) && StringUtils.isNotBlank(requestDTO.getConversationId())) {
                try {
                    Optional<ConversationView> convOpt = conversationRepository.get(requestDTO.getUserId(), UUID.fromString(requestDTO.getConversationId()));
                    if (convOpt.isPresent() && StringUtils.isNotBlank(convOpt.get().checkpointId())) {
                        checkpointId = convOpt.get().checkpointId();
                        if (checkpointRevision == null || checkpointRevision <= 0) {
                            checkpointRevision = convOpt.get().checkpointRevision();
                        }
                        log.info("从会话上下文自动恢复 Checkpoint: checkpointId={} revision={}", checkpointId, checkpointRevision);
                    }
                } catch (Exception ex) {
                    log.warn("无法从会话上下文恢复 Checkpoint", ex);
                }
            }

            if (StringUtils.isNotBlank(checkpointId)) {
                WorkflowCheckpointEntity existingCheckpoint = workflowCheckpointService.get(checkpointId);
                if (!finalSessionId.equals(existingCheckpoint.getSessionId()) || !requestDTO.getUserId().equals(existingCheckpoint.getUserId())) {
                    sessionExecutionGuard.release(executionLease.getAndSet(null));
                    throw new AppException("CHECKPOINT_OWNER_MISMATCH", "Checkpoint 与当前会话不匹配");
                }
                String pendingToolCallId = existingCheckpoint.getPendingToolCallId();
                if ("TOOL_APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision()) || "TOOL_DENY".equalsIgnoreCase(requestDTO.getCheckpointDecision())) {
                    if (StringUtils.isBlank(pendingToolCallId) || !pendingToolCallId.equals(requestDTO.getToolConfirmationCallId())) {
                        sessionExecutionGuard.release(executionLease.getAndSet(null));
                        throw new AppException("TOOL_CONFIRMATION_MISMATCH", "Tool 审批请求已失效，请刷新会话");
                    }
                    boolean expected = "TOOL_APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision());
                    if (requestDTO.getToolConfirmed() == null || requestDTO.getToolConfirmed() != expected) {
                        sessionExecutionGuard.release(executionLease.getAndSet(null));
                        throw new AppException("TOOL_CONFIRMATION_INVALID", "Tool 审批决策与 confirmed 字段不一致");
                    }
                }
                checkpoint = workflowCheckpointService.resume(
                        checkpointId,
                        checkpointRevision == null ? -1 : checkpointRevision,
                        requestDTO.getCheckpointDecision()
                );
                String decision = requestDTO.getCheckpointDecision();
                JSONObject approval = JSON.parseObject(checkpoint.getApprovalJson());
                String brief = approval == null ? "" : approval.getString("rewrittenPrompt");
                if ("REVISE".equalsIgnoreCase(decision)) {
                    if ("300000".equals(requestDTO.getAgentId())) entryAgentName = "agent_analyst";
                    effectiveMessage = "请修改最近的审核方案，不要开始绘图。修改意见：" + requestDTO.getMessage();
                } else if ("APPROVE".equalsIgnoreCase(decision)) {
                    if ("300000".equals(requestDTO.getAgentId())) entryAgentName = "agent_drawer";
                    if (StringUtils.isBlank(brief)) {
                        sessionExecutionGuard.release(executionLease.getAndSet(null));
                        throw new AppException("CHECKPOINT_APPROVAL_MISSING", "Checkpoint 不包含可批准的审核方案");
                    }
                    effectiveMessage = "[APPROVED_DRAWING_BRIEF]\n" + brief;
                } else {
                    effectiveMessage = StringUtils.isNotBlank(requestDTO.getMessage())
                            ? requestDTO.getMessage()
                            : "继续完成此前暂停的工作流。原始需求如下：\n" + checkpoint.getOriginalPrompt();
                }
            } else {
                checkpoint = workflowCheckpointService.start(requestDTO.getAgentId(), requestDTO.getUserId(), finalSessionId, requestDTO.getMessage());
                if ("300000".equals(requestDTO.getAgentId())) entryAgentName = "agent_analyst";
            }

            final String finalMessage = effectiveMessage;
            final String finalEntryAgentName = entryAgentName;
            final String messageIdempotencyPrefix = StringUtils.defaultIfBlank(requestDTO.getIdempotencyKey(), checkpoint.getCheckpointId() + ":" + checkpoint.getRevision());
            final UUID conversationId = StringUtils.isBlank(requestDTO.getConversationId()) ? null : UUID.fromString(requestDTO.getConversationId());
            String persistedUserMessage = requestDTO.getMessage();
            if (StringUtils.isBlank(persistedUserMessage) && "APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision())) persistedUserMessage = "确认并开始绘图";
            if (StringUtils.isBlank(persistedUserMessage) && "CONTINUE".equalsIgnoreCase(requestDTO.getCheckpointDecision())) persistedUserMessage = "继续执行此前暂停的任务";
            if (StringUtils.isBlank(persistedUserMessage) && "TOOL_APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision())) persistedUserMessage = "批准执行高风险工具";
            if (StringUtils.isBlank(persistedUserMessage) && "TOOL_DENY".equalsIgnoreCase(requestDTO.getCheckpointDecision())) persistedUserMessage = "拒绝执行高风险工具";
            if (conversationId != null && StringUtils.isNotBlank(persistedUserMessage)) {
                conversationRepository.append(requestDTO.getUserId(), conversationId, "user", "TEXT", persistedUserMessage, null, null, messageIdempotencyPrefix + ":user");
                conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, "RUNNING", null);
            }

            // 4. Create and persist Run record
            String runId = UUID.randomUUID().toString();
            Date now = new Date();
            ChatStreamRunEntity run = ChatStreamRunEntity.builder()
                    .runId(runId)
                    .userId(requestDTO.getUserId())
                    .sessionId(finalSessionId)
                    .conversationId(requestDTO.getConversationId())
                    .agentId(requestDTO.getAgentId())
                    .checkpointId(checkpoint.getCheckpointId())
                    .checkpointRevision(checkpoint.getRevision())
                    .idempotencyKey(requestDTO.getIdempotencyKey())
                    .status("RUNNING")
                    .lastSequenceNo(0L)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            ChatStreamRunEntity savedRun = chatStreamRunRepository.save(run);
            if (!savedRun.getRunId().equals(runId)) {
                sessionExecutionGuard.release(executionLease.getAndSet(null));
                log.info("Chat stream run concurrent idempotent duplicate caught runId:{} idempotencyKey:{}", savedRun.getRunId(), requestDTO.getIdempotencyKey());
                return Response.<ChatStreamRunResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(toDTO(savedRun))
                        .build();
            }

            // 5. Append initial checkpoint event before launching background
            JSONObject checkpointEnvelope = new JSONObject();
            checkpointEnvelope.put("phase", "thinking");
            JSONObject checkpointChunk = new JSONObject();
            checkpointChunk.put("type", "checkpoint");
            checkpointChunk.put("checkpointId", checkpoint.getCheckpointId());
            checkpointChunk.put("revision", checkpoint.getRevision());
            checkpointChunk.put("status", checkpoint.getStatus());
            checkpointEnvelope.put("chunk", checkpointChunk);
            chatStreamRunRepository.appendEvent(runId, "checkpoint", "thinking", checkpointEnvelope.toJSONString());

            // 6. Launch background workflow execution exactly once
            workflowExecutor.submit(() -> {
                try {
                    CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.CustomApiConfig.builder()
                            .baseUrl(requestDTO.getCustomBaseUrl())
                            .apiKey(requestDTO.getCustomApiKey())
                            .completionsPath(requestDTO.getCustomCompletionsPath())
                            .model(requestDTO.getCustomModel())
                            .customModelSelected(StringUtils.isNotBlank(requestDTO.getCustomModel()))
                            .build();
                    CustomApiConfigManager.setConfig(finalSessionId, config);

                    final ConcurrentHashMap<String, StringBuilder> authorBuffers = new ConcurrentHashMap<>();
                    final ConcurrentHashMap<String, StringBuilder> authorPartialTexts = new ConcurrentHashMap<>();
                    final java.util.Set<String> emittedStructuredLines = ConcurrentHashMap.newKeySet();
                    final ConcurrentHashMap<String, Long> toolStartedAt = new ConcurrentHashMap<>();
                    final AtomicReference<String> monitorInvocationId = new AtomicReference<>();
                    final AtomicBoolean invocationLinked = new AtomicBoolean(false);
                    final StringBuilder persistedAssistantText = new StringBuilder();

                    boolean toolDecision = "TOOL_APPROVE".equalsIgnoreCase(requestDTO.getCheckpointDecision()) || "TOOL_DENY".equalsIgnoreCase(requestDTO.getCheckpointDecision());
                    io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> eventStream = toolDecision
                            ? chatService.handleToolConfirmationStream(requestDTO.getAgentId(), requestDTO.getUserId(), finalSessionId, requestDTO.getToolConfirmationCallId(), Boolean.TRUE.equals(requestDTO.getToolConfirmed()), requestDTO.getToolConfirmationPayload(), requestDTO.getIdempotencyKey())
                            : chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(), finalSessionId, finalMessage, finalEntryAgentName, requestDTO.getIdempotencyKey());

                    eventStream.subscribe(
                            event -> {
                                try {
                                    if (monitorInvocationId.compareAndSet(null, event.invocationId()) && event.invocationId() != null) {
                                        runtimeObservationRepository.bindInvocationRequest(event.invocationId(), requestDTO.getIdempotencyKey());
                                    }
                                    if (conversationId != null && event.invocationId() != null && invocationLinked.compareAndSet(false, true)) {
                                        conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, "RUNNING", event.invocationId());
                                    }

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

                                    if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) {
                                        for (com.google.genai.types.FunctionCall call : event.functionCalls()) {
                                            String callId = call.id().orElseGet(() -> UUID.randomUUID().toString());
                                            long started = System.currentTimeMillis();
                                            toolStartedAt.put(callId, started);
                                            if ("adk_request_confirmation".equals(call.name().orElse(""))) {
                                                Map<String, Object> safeConfirmation = lightweightMonitorService.redactToolResult(call.args().orElse(Map.of()));
                                                String confirmationJson = JSON.toJSONString(safeConfirmation);
                                                WorkflowCheckpointEntity waiting = workflowCheckpointService.waitForToolApproval(checkpoint.getCheckpointId(), event.invocationId(), callId, confirmationJson);
                                                runtimeObservationRepository.workflowState(waiting.getSessionId(), waiting.getCheckpointId(), waiting.getStatus());
                                                JSONObject envelope = new JSONObject();
                                                envelope.put("phase", "thinking");
                                                JSONObject chunk = new JSONObject();
                                                chunk.put("type", "tool_approval");
                                                chunk.put("callId", callId);
                                                chunk.put("checkpointId", waiting.getCheckpointId());
                                                chunk.put("revision", waiting.getRevision());
                                                chunk.put("details", safeConfirmation);
                                                envelope.put("chunk", chunk);
                                                chatStreamRunRepository.appendEvent(runId, "tool_approval", "thinking", envelope.toJSONString());
                                                if (conversationId != null) {
                                                    conversationRepository.append(requestDTO.getUserId(), conversationId, "assistant", "TOOL_APPROVAL", "高风险工具等待批准", chunk.toJSONString(), event.invocationId(), messageIdempotencyPrefix + ":tool-approval:" + callId);
                                                }
                                                continue;
                                            }
                                            sendToolEvent(runId, phase, callId, call.name().orElse("unknown-tool"), "RUNNING", started, null);
                                        }
                                        for (com.google.genai.types.FunctionResponse response : event.functionResponses()) {
                                            String callId = response.id().orElseGet(() -> UUID.randomUUID().toString());
                                            long ended = System.currentTimeMillis();
                                            long started = toolStartedAt.getOrDefault(callId, ended);
                                            boolean failed = response.response().map(map -> map.containsKey("error") || Boolean.FALSE.equals(map.get("success"))).orElse(false);
                                            sendToolEvent(runId, phase, callId, response.name().orElse("unknown-tool"), failed ? "FAILED" : "SUCCESS", started, ended - started);
                                            toolStartedAt.remove(callId);
                                        }
                                        return;
                                    }

                                    String content = event.stringifyContent();
                                    if (content == null || content.isEmpty()) {
                                        return;
                                    }

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

                                    if (StringUtils.isNotEmpty(content)) {
                                        synchronized (persistedAssistantText) {
                                            persistedAssistantText.append(content);
                                        }
                                    }

                                    if ("agent_ppt_generator".equals(author) || "agent_ppt_reviewer".equals(author)) {
                                        JSONObject wrapper = new JSONObject();
                                        wrapper.put("phase", phase);
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("type", "ppt_raw");
                                        chunk.put("raw", content);
                                        wrapper.put("chunk", chunk);
                                        chatStreamRunRepository.appendEvent(runId, "ppt_raw", phase, wrapper.toJSONString());
                                        return;
                                    }

                                    StringBuilder buffer = authorBuffers.computeIfAbsent(author == null ? "unknown" : author, k -> new StringBuilder());
                                    String currentActiveLine;
                                    int lastNewline = buffer.lastIndexOf("\n");
                                    if (lastNewline >= 0) {
                                        currentActiveLine = buffer.substring(lastNewline + 1);
                                    } else {
                                        currentActiveLine = buffer.toString();
                                    }
                                    currentActiveLine = currentActiveLine.trim();
                                    boolean isLikelyJson = currentActiveLine.startsWith("{") || (currentActiveLine.isEmpty() && content.trim().startsWith("{"));

                                    if (!isLikelyJson && StringUtils.isNotEmpty(content)) {
                                        JSONObject tokenMsg = new JSONObject();
                                        tokenMsg.put("phase", phase);
                                        JSONObject tokenChunk = new JSONObject();
                                        tokenChunk.put("type", "token");
                                        tokenChunk.put("content", content);
                                        tokenMsg.put("chunk", tokenChunk);
                                        chatStreamRunRepository.appendEvent(runId, "token", phase, tokenMsg.toJSONString());
                                    }

                                    buffer.append(content);
                                    String accumulated = buffer.toString();
                                    if (isPartial) {
                                        if (accumulated.contains("\n")) {
                                            String[] lines = accumulated.split("\n", -1);
                                            buffer.setLength(0);
                                            buffer.append(lines[lines.length - 1]);
                                            for (int i = 0; i < lines.length - 1; i++) {
                                                processAndPersistUniqueLine(runId, phase, lines[i], checkpoint,
                                                        monitorInvocationId.get(), emittedStructuredLines);
                                            }
                                        }
                                    } else {
                                        buffer.setLength(0);
                                        for (String line : accumulated.split("\n")) {
                                            processAndPersistUniqueLine(runId, phase, line, checkpoint,
                                                    monitorInvocationId.get(), emittedStructuredLines);
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("处理事件异常 runId:{} sessionId:{} reason:{}", runId, finalSessionId, e.getMessage());
                                }
                            },
                            error -> {
                                sessionExecutionGuard.release(executionLease.getAndSet(null));
                                CustomApiConfigManager.clearConfig(finalSessionId);
                                String invocationId = monitorInvocationId.get();
                                if (invocationId != null) lightweightMonitorService.runCompleted(invocationId, false, error.getMessage());
                                workflowCheckpointService.finish(checkpoint.getCheckpointId(), false, error.getMessage());
                                WorkflowCheckpointEntity failedCheckpoint = workflowCheckpointService.get(checkpoint.getCheckpointId());
                                runtimeObservationRepository.workflowState(failedCheckpoint.getSessionId(), failedCheckpoint.getCheckpointId(), failedCheckpoint.getStatus());
                                if (conversationId != null) conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, "FAILED", invocationId);
                                log.error("流式对话执行失败 runId:{} sessionId:{}", runId, finalSessionId, error);

                                JSONObject errMsg = new JSONObject();
                                errMsg.put("phase", "error");
                                JSONObject chunk = new JSONObject();
                                chunk.put("type", "error");
                                chunk.put("content", "对话异常，请重试");
                                errMsg.put("chunk", chunk);
                                chatStreamRunRepository.appendEvent(runId, "error", "error", errMsg.toJSONString());
                                chatStreamRunRepository.updateStatus(runId, "FAILED", error.getMessage());
                            },
                            () -> {
                                sessionExecutionGuard.release(executionLease.getAndSet(null));
                                String invocationId = monitorInvocationId.get();
                                if (invocationId != null) lightweightMonitorService.runCompleted(invocationId, true, "");

                                for (StringBuilder buf : authorBuffers.values()) {
                                    String remaining = buf.toString().trim();
                                    if (!remaining.isEmpty()) {
                                        try {
                                            processAndPersistLine(runId, "done", remaining, checkpoint, monitorInvocationId.get());
                                        } catch (Exception ignored) {}
                                    }
                                }

                                JSONObject doneMsg = new JSONObject();
                                doneMsg.put("phase", "done");
                                JSONObject chunk = new JSONObject();
                                chunk.put("type", "done");
                                doneMsg.put("chunk", chunk);
                                chatStreamRunRepository.appendEvent(runId, "done", "done", doneMsg.toJSONString());

                                workflowCheckpointService.finish(checkpoint.getCheckpointId(), true, "");
                                try {
                                    WorkflowCheckpointEntity persistedCheckpoint = workflowCheckpointService.get(checkpoint.getCheckpointId());
                                    runtimeObservationRepository.workflowState(persistedCheckpoint.getSessionId(), persistedCheckpoint.getCheckpointId(), persistedCheckpoint.getStatus());
                                    if (conversationId != null) {
                                        String rawAssistant;
                                        synchronized (persistedAssistantText) {
                                            rawAssistant = persistedAssistantText.toString();
                                        }
                                        String drawioXml = extractStructuredContent(rawAssistant, "drawio_done", "content");
                                        if (StringUtils.isBlank(drawioXml)) drawioXml = extractStructuredContent(rawAssistant, "drawio", "content");
                                        String approvalJson = persistedCheckpoint.getApprovalJson();
                                        if (WorkflowCheckpointService.WAITING_APPROVAL.equals(persistedCheckpoint.getStatus()) && StringUtils.isNotBlank(approvalJson)) {
                                            JSONObject approval = JSON.parseObject(approvalJson);
                                            String approvalText = approval == null ? "等待审核" : StringUtils.defaultIfBlank(approval.getString("rewrittenPrompt"), "等待审核");
                                            conversationRepository.append(requestDTO.getUserId(), conversationId, "assistant", "APPROVAL", approvalText, approvalJson, invocationId, messageIdempotencyPrefix + ":approval");
                                        }
                                        if (StringUtils.isNotBlank(drawioXml)) {
                                            JSONObject payload = new JSONObject();
                                            payload.put("xml", drawioXml);
                                            payload.put("checkpointId", checkpoint.getCheckpointId());
                                            String approvedBrief = "";
                                            if (StringUtils.isNotBlank(approvalJson)) {
                                                JSONObject approved = JSON.parseObject(approvalJson);
                                                if (approved != null) approvedBrief = StringUtils.defaultString(approved.getString("rewrittenPrompt"));
                                            }
                                            String completedReply = (StringUtils.isBlank(approvedBrief) ? "已按审核方案完成绘图。" : "已按审核方案完成绘图：\n\n" + approvedBrief) + "\n\n图表已生成完成，可在画布中继续编辑或导出。";
                                            conversationRepository.append(requestDTO.getUserId(), conversationId, "assistant", "DRAWIO", completedReply, payload.toJSONString(), invocationId, messageIdempotencyPrefix + ":drawio-message");
                                            artifactRepository.save(conversationId, invocationId, "DRAWIO", "Draw.io 图表", "application/vnd.jgraph.mxfile", drawioXml, "{}", messageIdempotencyPrefix + ":drawio-artifact");
                                        } else {
                                            String assistant = cleanPersistedAssistant(rawAssistant);
                                            if (StringUtils.isNotBlank(assistant)) {
                                                conversationRepository.append(requestDTO.getUserId(), conversationId, "assistant", "TEXT", assistant, null, invocationId, messageIdempotencyPrefix + ":assistant-message");
                                                artifactRepository.save(conversationId, invocationId, assistant.contains("\"type\":\"ppt\"") ? "PPT" : "AGENT_OUTPUT", "Agent 输出", assistant.contains("\"type\":\"ppt\"") ? "application/json" : "text/markdown", assistant, "{}", messageIdempotencyPrefix + ":agent-artifact");
                                            }
                                        }
                                        String finalStatus = WorkflowCheckpointService.WAITING_APPROVAL.equals(persistedCheckpoint.getStatus()) ? "WAITING_APPROVAL" : WorkflowCheckpointService.WAITING_TOOL_APPROVAL.equals(persistedCheckpoint.getStatus()) ? "WAITING_TOOL_APPROVAL" : "COMPLETED";
                                        conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, finalStatus, invocationId);
                                    }
                                } catch (Exception persistenceError) {
                                    log.error("Agent 已完成但会话产物持久化失败 sessionId:{} invocationId:{}", finalSessionId, invocationId, persistenceError);
                                    if (conversationId != null) conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, "FAILED", invocationId);
                                } finally {
                                    CustomApiConfigManager.clearConfig(finalSessionId);
                                    chatStreamRunRepository.updateStatus(runId, "COMPLETED", null);
                                }
                            }
                    );
                } catch (Exception e) {
                    sessionExecutionGuard.release(executionLease.getAndSet(null));
                    CustomApiConfigManager.clearConfig(finalSessionId);
                    log.error("后台工作流启动失败 runId:{} sessionId:{}", runId, finalSessionId, e);
                    JSONObject errMsg = new JSONObject();
                    errMsg.put("phase", "error");
                    JSONObject chunk = new JSONObject();
                    chunk.put("type", "error");
                    chunk.put("content", "工作流启动失败: " + e.getMessage());
                    errMsg.put("chunk", chunk);
                    try {
                        chatStreamRunRepository.appendEvent(runId, "error", "error", errMsg.toJSONString());
                    } catch (Exception appendEx) {
                        log.warn("无法追加启动失败事件 runId:{}", runId, appendEx);
                    }
                    chatStreamRunRepository.updateStatus(runId, "FAILED", e.getMessage());
                    if (conversationId != null) {
                        conversationRepository.updateStatus(requestDTO.getUserId(), conversationId, "FAILED", null);
                    }
                    try {
                        workflowCheckpointService.finish(checkpoint.getCheckpointId(), false, e.getMessage());
                    } catch (Exception ignored) {}
                }
            });

            return Response.<ChatStreamRunResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toDTO(run))
                    .build();

        } catch (AppException e) {
            sessionExecutionGuard.release(executionLease.getAndSet(null));
            log.error("创建流式 Run 异常", e);
            return Response.<ChatStreamRunResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            sessionExecutionGuard.release(executionLease.getAndSet(null));
            log.error("创建流式 Run 失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatStreamRunResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping(value = {"chat_stream/{runId}", "chat_stream"}, produces = "text/event-stream;charset=UTF-8")
    public SseEmitter subscribeChatStream(
            @PathVariable(value = "runId", required = false) String pathRunId,
            @RequestParam(value = "runId", required = false) String paramRunId,
            @RequestParam(value = "after", required = false) Long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {
        String effectiveRunId = StringUtils.isNotBlank(pathRunId) ? pathRunId : paramRunId;
        return doSubscribeChatStream(effectiveRunId, after, lastEventId, response);
    }

    @Override
    public SseEmitter subscribeChatStream(String runId, Long after, String lastEventId) {
        return doSubscribeChatStream(runId, after, lastEventId, null);
    }

    public static long resolveCursor(Long after, String lastEventId) {
        if (StringUtils.isNotBlank(lastEventId)) {
            try {
                long parsed = Long.parseLong(lastEventId.trim());
                if (parsed >= 0) {
                    return parsed;
                }
            } catch (Exception ignored) {}
        }
        if (after != null && after >= 0) {
            return after;
        }
        return 0L;
    }

    private SseEmitter doSubscribeChatStream(String runId, Long after, String lastEventId, HttpServletResponse response) {
        if (StringUtils.isBlank(runId)) {
            throw new AppException("RUN_ID_REQUIRED", "RunId 不能为空");
        }

        Optional<ChatStreamRunEntity> runOpt = chatStreamRunRepository.findById(runId);
        if (runOpt.isEmpty()) {
            throw new AppException("RUN_NOT_FOUND", "Run " + runId + " 不存在");
        }
        ChatStreamRunEntity run = runOpt.get();
        AuthenticatedUserContext.require(run.getUserId());

        if (response != null) {
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
        }

        long cursor = resolveCursor(after, lastEventId);

        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        AtomicLong currentCursor = new AtomicLong(cursor);
        AtomicLong lastHeartbeatAt = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        ScheduledFuture<?>[] pollTaskRef = new ScheduledFuture<?>[1];

        Runnable cancelTask = () -> {
            if (closed.compareAndSet(false, true)) {
                ScheduledFuture<?> task = pollTaskRef[0];
                if (task != null) task.cancel(true);
            }
        };

        emitter.onCompletion(cancelTask);
        emitter.onTimeout(() -> {
            cancelTask.run();
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        });
        emitter.onError(e -> cancelTask.run());

        pollTaskRef[0] = scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            try {
                List<ChatStreamEventEntity> events = chatStreamRunRepository.queryEventsAfter(runId, currentCursor.get(), 100);
                for (ChatStreamEventEntity event : events) {
                    if (closed.get()) return;
                    Object dataObj;
                    try {
                        dataObj = JSON.parse(event.getDataJson());
                    } catch (Exception ex) {
                        dataObj = event.getDataJson();
                    }
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(event.getSequenceNo()))
                            .name(event.getEventType())
                            .data(dataObj, MediaType.APPLICATION_JSON));
                    currentCursor.set(event.getSequenceNo());
                    lastHeartbeatAt.set(System.currentTimeMillis());
                }

                if (events.isEmpty()) {
                    if (System.currentTimeMillis() - lastHeartbeatAt.get() >= 15000) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        lastHeartbeatAt.set(System.currentTimeMillis());
                    }
                }

                Optional<ChatStreamRunEntity> latestRun = chatStreamRunRepository.findById(runId);
                if (latestRun.isPresent()) {
                    String st = latestRun.get().getStatus();
                    if (!"RUNNING".equals(st)) {
                        if (currentCursor.get() >= latestRun.get().getLastSequenceNo()) {
                            cancelTask.run();
                            emitter.complete();
                        }
                    }
                }
            } catch (Exception ex) {
                cancelTask.run();
                try {
                    if (isClientAbort(ex)) {
                        emitter.complete();
                    } else {
                        emitter.completeWithError(ex);
                    }
                } catch (Exception ignored) {}
            }
        }, 0, 250, TimeUnit.MILLISECONDS);

        return emitter;
    }

    private boolean isClientAbort(Throwable t) {
        if (t == null) return false;
        String name = t.getClass().getName();
        String msg = t.getMessage() == null ? "" : t.getMessage();
        if (name.contains("ClientAbortException") || name.contains("AsyncRequestNotUsableException")) {
            return true;
        }
        if (msg.contains("中止了一个已建立的连接") || msg.contains("Connection reset") || msg.contains("Broken pipe")) {
            return true;
        }
        return isClientAbort(t.getCause());
    }

    @GetMapping(value = {"chat_stream/active_run", "chat_stream/active"})
    @Override
    public Response<ChatStreamRunResponseDTO> queryActiveRun(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        try {
            String userId = AuthenticatedUserContext.current();
            Optional<ChatStreamRunEntity> active = chatStreamRunRepository.findActiveRun(userId, sessionId, conversationId);
            return Response.<ChatStreamRunResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(active.map(this::toDTO).orElse(null))
                    .build();
        } catch (AppException e) {
            return Response.<ChatStreamRunResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            return Response.<ChatStreamRunResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @GetMapping("monitor/workflows/{taskId}")
    public Response<Map<String, Object>> workflowDetail(@PathVariable String taskId) {
        return Response.<Map<String, Object>>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(runtimeObservationRepository.workflowDetail(AuthenticatedUserContext.current(), taskId)).build();
    }

    @GetMapping("monitor/sessions/{sessionId}/invocations")
    public Response<List<Map<String, Object>>> monitorSession(@PathVariable String sessionId) {
        return Response.<List<Map<String, Object>>>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                .data(runtimeObservationRepository.listBySession(AuthenticatedUserContext.current(), sessionId)).build();
    }

    private ChatStreamRunResponseDTO toDTO(ChatStreamRunEntity e) {
        if (e == null) return null;
        return ChatStreamRunResponseDTO.builder()
                .runId(e.getRunId())
                .agentId(e.getAgentId())
                .userId(e.getUserId())
                .sessionId(e.getSessionId())
                .conversationId(e.getConversationId())
                .checkpointId(e.getCheckpointId())
                .checkpointRevision(e.getCheckpointRevision())
                .status(e.getStatus())
                .lastSequenceNo(e.getLastSequenceNo())
                .errorMessage(e.getErrorMessage())
                .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().getTime() : null)
                .updatedAt(e.getUpdatedAt() != null ? e.getUpdatedAt().getTime() : null)
                .build();
    }

    private boolean processAndPersistLine(String runId, String phase, String line,
                                         WorkflowCheckpointEntity checkpoint, String invocationId) {
        try {
            JSONObject json = JSON.parseObject(line);
            if (json != null && json.containsKey("type")) {
                String type = json.getString("type");
                if ("approval".equals(type)) {
                    if (!json.containsKey("rewrittenPrompt")) {
                        String rewritten = json.getString("rewedPrompt");
                        if (StringUtils.isBlank(rewritten)) rewritten = json.getString("rewritePrompt");
                        if (StringUtils.isNotBlank(rewritten)) json.put("rewrittenPrompt", rewritten);
                    }
                    json.remove("rewedPrompt");
                    json.remove("rewritePrompt");
                    json.put("checkpointId", checkpoint.getCheckpointId());
                    json.put("revision", checkpoint.getRevision() + 1);
                    json.put("checkpointStatus", WorkflowCheckpointService.WAITING_APPROVAL);
                    WorkflowCheckpointEntity saved = workflowCheckpointService.approval(checkpoint.getCheckpointId(), invocationId, json.toJSONString());
                    runtimeObservationRepository.workflowState(saved.getSessionId(), saved.getCheckpointId(), saved.getStatus());
                    json.put("revision", saved.getRevision());
                    json.put("checkpointStatus", saved.getStatus());
                }
                if ("drawio_node".equals(type) || "drawio_edge".equals(type) || "drawio_done".equals(type)
                        || "user".equals(type) || "approval".equals(type) || "drawio".equals(type)) {
                    JSONObject wrapper = new JSONObject();
                    wrapper.put("phase", phase);
                    wrapper.put("chunk", json);
                    chatStreamRunRepository.appendEvent(runId, type, phase, wrapper.toJSONString());
                    return "approval".equals(type);
                }

                if ("ppt".equals(type) || json.containsKey("slides")) {
                    JSONObject wrapper = new JSONObject();
                    wrapper.put("phase", phase);
                    JSONObject chunk = new JSONObject();
                    chunk.put("type", "ppt_raw");
                    chunk.put("raw", line);
                    wrapper.put("chunk", chunk);
                    chatStreamRunRepository.appendEvent(runId, "ppt_raw", phase, wrapper.toJSONString());
                    return false;
                }
            }
        } catch (Exception ignored) {}

        if (line.contains("\"slides\"") || line.contains("\"slideIndex\"") || line.contains("\"elements\"")) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("phase", phase);
            JSONObject chunk = new JSONObject();
            chunk.put("type", "ppt_raw");
            chunk.put("raw", line);
            wrapper.put("chunk", chunk);
            chatStreamRunRepository.appendEvent(runId, "ppt_raw", phase, wrapper.toJSONString());
            return false;
        }

        JSONObject statusMsg = new JSONObject();
        JSONObject chunk = new JSONObject();
        chunk.put("type", "status");
        chunk.put("content", line);
        statusMsg.put("phase", phase);
        statusMsg.put("chunk", chunk);
        chatStreamRunRepository.appendEvent(runId, "status", phase, statusMsg.toJSONString());
        return false;
    }

    private boolean processAndPersistUniqueLine(String runId, String phase, String rawLine,
                                               WorkflowCheckpointEntity checkpoint, String invocationId,
                                               java.util.Set<String> emittedStructuredLines) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty()) return false;
        boolean structured = line.startsWith("{") && line.endsWith("}");
        if (structured && !emittedStructuredLines.add(line)) return false;
        return processAndPersistLine(runId, phase, line, checkpoint, invocationId);
    }

    private void sendToolEvent(String runId, String phase, String callId, String name, String status, long startedAt, Long durationMs) {
        JSONObject envelope = new JSONObject();
        envelope.put("phase", phase);
        JSONObject chunk = new JSONObject();
        chunk.put("type", "tool");
        chunk.put("callId", callId);
        chunk.put("name", name);
        chunk.put("status", status);
        chunk.put("startedAt", startedAt);
        if (durationMs != null) chunk.put("durationMs", durationMs);
        envelope.put("chunk", chunk);
        chatStreamRunRepository.appendEvent(runId, "tool", phase, envelope.toJSONString());
    }

    private String extractStructuredContent(String raw, String expectedType, String field) {
        if (StringUtils.isBlank(raw)) return "";
        String found = "";
        for (String source : raw.split("\\R")) {
            int start = source.indexOf("{\"type\"");
            if (start < 0) continue;
            try {
                JSONObject json = JSON.parseObject(source.substring(start).trim());
                if (expectedType.equals(json.getString("type"))) found = StringUtils.defaultString(json.getString(field));
            } catch (Exception ignored) {}
        }
        return found;
    }

    private String cleanPersistedAssistant(String raw) {
        if (StringUtils.isBlank(raw)) return "";
        StringBuilder clean = new StringBuilder();
        for (String source : raw.split("\\R")) {
            String line = source.trim();
            if (line.isBlank() || line.startsWith("[APPROVED_DRAWING_BRIEF]")) continue;
            int json = line.indexOf("{\"type\"");
            if (json == 0) continue;
            if (json > 0) line = line.substring(0, json).trim();
            if (!line.isBlank()) clean.append(line).append('\n');
        }
        return clean.toString().trim();
    }

}
