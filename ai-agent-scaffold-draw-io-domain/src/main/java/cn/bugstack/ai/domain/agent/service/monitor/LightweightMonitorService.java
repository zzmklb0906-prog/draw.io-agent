package cn.bugstack.ai.domain.agent.service.monitor;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeObservationRepository;
import cn.bugstack.ai.domain.agent.model.valobj.CapabilityDescriptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class LightweightMonitorService {

    private static final int MAX_INVOCATIONS = 200;
    private final Map<String, InvocationRecord> records = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();
    private final Set<String> registeredTools = ConcurrentHashMap.newKeySet();
    /** Explicit ADK run context. Keys include branch so parallel branches never compete for a "latest" DB row. */
    private final Map<String, RunHandle> activeRuns = new ConcurrentHashMap<>();
    private IRuntimeObservationRepository persistence;

    @Autowired(required = false)
    public void setPersistence(IRuntimeObservationRepository persistence) { this.persistence = persistence; }

    public void registerTool(String name) { if (name != null && !name.isBlank()) registeredTools.add(name); }

    /** Returns an invocation only when correlation is unambiguous. */
    public Optional<String> soleActiveInvocationId() {
        String active = null;
        for (InvocationRecord record : records.values()) {
            if (!"RUNNING".equals(record.status)) continue;
            if (active != null) return Optional.empty();
            active = record.invocationId;
        }
        return Optional.ofNullable(active);
    }

    public String activeAgentName(String invocationId) {
        InvocationRecord record = records.get(invocationId);
        if (record == null) return "unknown";
        return record.agents.values().stream()
                .filter(agent -> agent.startedAt.get() > 0 && agent.completedAt.get() == 0)
                .max(Comparator.comparingLong(agent -> agent.startedAt.get()))
                .map(agent -> agent.name)
                .orElse(record.rootAgent);
    }

    public synchronized void runStarted(String invocationId, String sessionId, String userId, String rootAgent) {
        runStarted(invocationId,sessionId,userId,rootAgent,rootAgent);
    }

    public synchronized void runStarted(String invocationId, String sessionId, String userId, String rootAgent, String appName) {
        InvocationRecord record = records.get(invocationId);
        if (record == null) {
            order.addFirst(invocationId);
            while (order.size() > MAX_INVOCATIONS) records.remove(order.removeLast());
            record = new InvocationRecord(invocationId, sessionId, userId, rootAgent, appName);
            records.put(invocationId, record);
        } else if ("unknown".equalsIgnoreCase(record.rootAgent) && rootAgent != null && !"unknown".equalsIgnoreCase(rootAgent)) {
            record.updateMetadata(sessionId, userId, rootAgent, appName);
        }
        InvocationRecord finalRecord = record;
        persist(() -> persistence.invocationStarted(invocationId, sessionId, userId, rootAgent, appName, finalRecord.startedAt));
    }

    public void versionSnapshot(String invocationId,Map<String,Object> snapshot){if(snapshot==null||snapshot.isEmpty())return;persist(()->persistence.invocationVersionSnapshot(invocationId,snapshot));}

    public void runCompleted(String invocationId, boolean success, String error) {
        InvocationRecord record = records.get(invocationId);
        if (record == null) return;
        synchronized (record) {
            if (record.completedAt > 0) return;
            record.completedAt = System.currentTimeMillis();
            record.status = success ? "SUCCESS" : "ERROR";
            record.error = error == null ? "" : error;
        }
        long now = record.completedAt;
        record.agents.values().forEach(agent -> agent.forceComplete(now));
        record.tools.values().forEach(tool -> { if (tool.completedAt == 0) { tool.completedAt = now; tool.status = success ? "INTERRUPTED" : "ERROR"; } });
        persist(()->persistence.invocationCompleted(invocationId,record.status,record.completedAt,record.durationMs(),record.inputTokens.get(),record.outputTokens.get(),record.error));
    }

    public void agentStarted(String invocationId, String agentName) {
        agentStarted(invocationId, agentName, null, "", null);
    }

    public String agentStarted(String invocationId, String agentName, String parentAgentName, String branch, String forcedParentRunId) {
        InvocationRecord record = ensure(invocationId);
        long startedAt=System.currentTimeMillis();
        record.agents.computeIfAbsent(agentName, AgentRecord::new).startedAt.compareAndSet(0, startedAt);
        AgentRecord agent=record.agents.get(agentName);
        String runId=UUID.randomUUID().toString();
        String parentRunId=forcedParentRunId;
        if(parentRunId==null||parentRunId.isBlank()) parentRunId=resolveActiveRun(invocationId,parentAgentName,branch);
        RunHandle handle=new RunHandle(runId,parentRunId,invocationId,agentName,normalizeBranch(branch),startedAt);
        activeRuns.put(runKey(invocationId,agentName,branch),handle);
        String finalParentRunId=parentRunId;
        persist(()->persistence.agentStarted(invocationId,runId,finalParentRunId,agentName,normalizeBranch(branch),handle.startedAt));
        return runId;
    }

    public void agentCompleted(String invocationId, String agentName) {
        agentCompleted(invocationId,agentName,resolveActiveRun(invocationId,agentName,""),"");
    }

    public void agentCompleted(String invocationId, String agentName, String runId, String branch) {
        AgentRecord agent = ensure(invocationId).agents.computeIfAbsent(agentName, AgentRecord::new);
        long completedAt=System.currentTimeMillis();
        agent.completedAt.set(completedAt);
        agent.finishModel(completedAt);
        String resolved=runId==null||runId.isBlank()?resolveActiveRun(invocationId,agentName,branch):runId;
        RunHandle handle=activeRuns.get(runKey(invocationId,agentName,branch));
        long runDuration=handle==null?Math.max(0,completedAt-agent.startedAt.get()):Math.max(0,completedAt-handle.startedAt);
        persist(()->persistence.agentCompleted(invocationId,resolved,agentName,completedAt,runDuration,agent.modelCalls.get(),agent.modelDurationMs.get(),agent.inputTokens.get(),agent.outputTokens.get()));
        activeRuns.remove(runKey(invocationId,agentName,branch));
    }

    public void modelStarted(String invocationId, String agentName) {
        modelStarted(invocationId,agentName,resolveActiveRun(invocationId,agentName,""));
    }
    public void modelStarted(String invocationId, String agentName,String runId) {
        AgentRecord agent = ensure(invocationId).agents.computeIfAbsent(agentName, AgentRecord::new);
        agent.modelStartedAt.set(System.currentTimeMillis());
        agent.modelCalls.incrementAndGet();
        String actualModel = (agent.lastRoutedModel != null && !agent.lastRoutedModel.isBlank()) ? agent.lastRoutedModel : agentName;
        persist(()->persistence.modelStarted(invocationId,runId,agentName,actualModel,agent.modelStartedAt.get(),agent.inputTokens.get()));
    }

    public void modelCompleted(String invocationId, String agentName) {
        modelCompleted(invocationId,agentName,resolveActiveRun(invocationId,agentName,""));
    }
    public void modelCompleted(String invocationId, String agentName,String runId) {
        AgentRecord agent = ensure(invocationId).agents.computeIfAbsent(agentName, AgentRecord::new);
        long start = agent.modelStartedAt.getAndSet(0);
        long now=System.currentTimeMillis(),duration=start>0?now-start:0;
        if (start > 0) agent.modelDurationMs.addAndGet(duration);
        persist(()->persistence.modelCompleted(invocationId,runId,agentName,now,duration,agent.inputTokens.get(),agent.outputTokens.get(),"SUCCESS",""));
    }

    public void modelFailed(String invocationId,String agentName,Throwable error){
        modelFailed(invocationId,agentName,resolveActiveRun(invocationId,agentName,""),error);
    }
    public void modelFailed(String invocationId,String agentName,String runId,Throwable error){
        AgentRecord agent=ensure(invocationId).agents.computeIfAbsent(agentName,AgentRecord::new);
        long start=agent.modelStartedAt.getAndSet(0),now=System.currentTimeMillis(),duration=start>0?now-start:0;
        if(start>0)agent.modelDurationMs.addAndGet(duration);
        String message=error==null?"Model call failed":truncate(error.getMessage(),1000);
        persist(()->persistence.modelCompleted(invocationId,runId,agentName,now,duration,agent.inputTokens.get(),agent.outputTokens.get(),"ERROR",message));
        // A provider failure may occur before ADK emits an Event or invokes afterRun.
        // Finalize here as the lifecycle service's invariant; duplicate completion is idempotent.
        runCompleted(invocationId,false,message);
    }

    public void modelRouted(String invocationId, String agentName, String model, String reason, int complexity, boolean explicit) {
        modelRouted(invocationId, agentName, model, reason, complexity, explicit, "", Map.of(), List.of(), List.of());
    }

    public void modelRouted(String invocationId, String agentName, String model, String reason, int complexity, boolean explicit,
                            String narrative, Map<String, Object> metrics, List<String> matchedKeywords, List<Map<String, Object>> pipelineTrail) {
        if (invocationId == null || invocationId.isBlank()) return;
        InvocationRecord record = ensure(invocationId);
        String targetAgent = (agentName == null || agentName.isBlank()) ? "unknown" : agentName;
        AgentRecord agent = record.agents.computeIfAbsent(targetAgent, AgentRecord::new);
        if (model != null && !model.isBlank()) agent.lastRoutedModel = model;

        Map<String, Object> decisionMap = new LinkedHashMap<>();
        decisionMap.put("agentName", targetAgent);
        decisionMap.put("model", model == null ? "" : model);
        decisionMap.put("reason", reason == null ? "" : reason);
        decisionMap.put("complexity", complexity);
        decisionMap.put("explicit", explicit);
        decisionMap.put("narrative", narrative == null ? "" : narrative);
        decisionMap.put("metrics", metrics == null ? Map.of() : metrics);
        decisionMap.put("matchedKeywords", matchedKeywords == null ? List.of() : matchedKeywords);
        decisionMap.put("pipelineTrail", pipelineTrail == null ? List.of() : pipelineTrail);
        decisionMap.put("timestamp", System.currentTimeMillis());

        record.modelDecisions.add(decisionMap);
        String selected=model==null?"":model;
        persist(()->persistence.invocationModelVersion(invocationId,selected,InvocationVersionCatalog.fingerprint(selected)));
        runtimeEvent(invocationId,"MODEL_ROUTED",decisionMap);
    }

    public void usage(String invocationId, String agentName, int input, int output, int total) {
        InvocationRecord record = ensure(invocationId);
        AgentRecord agent = record.agents.computeIfAbsent(agentName, AgentRecord::new);
        if (input > 0) agent.inputTokens.set(input);
        if (output > 0) agent.outputTokens.set(output);
        if (total > 0) agent.totalTokens.set(total);
        if (input > 0 || output > 0 || total > 0) agent.tokensEstimated = false;
        record.recalculateTokens();
    }

    public void providerUsage(String invocationId, String agentName, int input, int output, int total) {
        if (invocationId == null || invocationId.isBlank()) return;
        InvocationRecord record = ensure(invocationId);
        AgentRecord agent = record.agents.computeIfAbsent(agentName, AgentRecord::new);
        if (!agent.providerUsageSeen) {
            agent.inputTokens.set(0); agent.outputTokens.set(0); agent.totalTokens.set(0); agent.providerUsageSeen = true;
        }
        agent.inputTokens.addAndGet(Math.max(0, input));
        agent.outputTokens.addAndGet(Math.max(0, output));
        agent.totalTokens.addAndGet(total > 0 ? total : Math.max(0, input) + Math.max(0, output));
        agent.tokensEstimated = false;
        record.recalculateTokens();
    }

    public void estimatedInput(String invocationId, String agentName, int tokens) {
        AgentRecord agent = ensure(invocationId).agents.computeIfAbsent(agentName, AgentRecord::new);
        if (agent.inputTokens.get() == 0) agent.inputTokens.set(Math.max(0, tokens));
        agent.totalTokens.set(agent.inputTokens.get() + agent.outputTokens.get());
        ensure(invocationId).recalculateTokens();
    }

    public void estimatedOutput(String invocationId, String agentName, int tokens) {
        AgentRecord agent = ensure(invocationId).agents.computeIfAbsent(agentName, AgentRecord::new);
        if (agent.tokensEstimated) agent.outputTokens.addAndGet(Math.max(0, tokens));
        agent.totalTokens.set(agent.inputTokens.get() + agent.outputTokens.get());
        ensure(invocationId).recalculateTokens();
    }

    public void toolStarted(String invocationId, String agentName, String callId, String toolName) { toolStarted(invocationId,agentName,callId,toolName,Map.of()); }
    public void toolStarted(String invocationId, String agentName, String callId, String toolName,Map<String,Object> arguments) {
        toolStarted(invocationId,agentName,resolveActiveRun(invocationId,agentName,""),callId,toolName,arguments);
    }
    public void toolStarted(String invocationId, String agentName,String runId, String callId, String toolName,Map<String,Object> arguments) {
        toolStarted(invocationId,agentName,runId,callId,toolName,arguments,Map.of());
    }
    public void toolStarted(String invocationId, String agentName,String runId, String callId, String toolName,Map<String,Object> arguments,Map<String,Object> governancePolicy) {
        InvocationRecord record = ensure(invocationId);
        ToolRecord tool = new ToolRecord(callId, agentName, toolName);
        record.tools.put(callId, tool);
        String safeArguments=com.alibaba.fastjson.JSON.toJSONString(redact(arguments));
        Map<String,Object> safePolicy=safeMap(governancePolicy);persist(()->persistence.toolStarted(invocationId,runId,agentName,callId,toolName,tool.startedAt,safeArguments,safePolicy));
    }

    public String toolCompleted(String invocationId, String callId, boolean success, String summary) {
        ToolRecord tool = ensure(invocationId).tools.get(callId);
        if (tool == null) return "";
        String persistedSummary=redactText(summary);
        tool.completedAt = System.currentTimeMillis();
        tool.status = success ? "SUCCESS" : "ERROR";
        tool.summary = truncate(summary, 240);
        if(persistence==null)return "";
        try{return Objects.toString(persistence.toolCompleted(invocationId,callId,tool.status,tool.completedAt,tool.completedAt-tool.startedAt,persistedSummary,0,success?"":redactText(tool.summary)),"");}
        catch(Exception error){log.warn("Tool 观测数据持久化失败，主流程继续",error);return "";}
    }
    public void toolRetry(String invocationId,String callId,int attemptNo,Throwable error){long at=System.currentTimeMillis();persist(()->persistence.toolRetry(invocationId,callId,attemptNo,at,error==null?"":redactText(String.valueOf(error.getMessage()))));}

    public void eventSeen(String invocationId) { ensure(invocationId).eventCount.incrementAndGet(); }

    public void compression(String invocationId, int before, int after, String strategy, long durationMs) {
        InvocationRecord record = ensure(invocationId);
        record.compressions.add(Map.of("beforeTokens", before, "afterTokens", after,
                "strategy", strategy, "durationMs", durationMs, "timestamp", System.currentTimeMillis()));
        runtimeEvent(invocationId,"CONTEXT_COMPRESSED",Map.of("beforeTokens",before,"afterTokens",after,"strategy",strategy,"durationMs",durationMs));
    }

    public void capabilitySearch(String invocationId,String agentName,String parentToolCallId,String snapshotId,String query,List<String> requestedTypes,List<Map<String,Object>> candidates,int registrySize,long startedAt) {
        if (invocationId == null || invocationId.isBlank()) return;
        long completedAt=System.currentTimeMillis(),duration=Math.max(0,completedAt-startedAt);String searchId=UUID.randomUUID().toString();
        List<String> capabilityIds=candidates.stream().map(c->Objects.toString(c.get("capabilityId"),"")).toList();
        Map<String,Object> event=new LinkedHashMap<>();event.put("event","SEARCH");event.put("searchId",searchId);event.put("snapshotId",snapshotId);event.put("query",truncate(query,160));event.put("requestedTypes",requestedTypes);event.put("capabilityIds",capabilityIds);event.put("candidates",candidates);event.put("registrySize",registrySize);event.put("durationMs",duration);event.put("timestamp",completedAt);ensure(invocationId).capabilityEvents.add(event);
        runtimeEvent(invocationId,"CAPABILITY_SEARCHED",event);
        String runId=resolveActiveRun(invocationId,agentName,"");
        persist(()->persistence.capabilitySearch(searchId,invocationId,runId,agentName,parentToolCallId,snapshotId,query,requestedTypes,registrySize,candidates,startedAt,completedAt,duration,"SUCCESS",""));
    }

    public String capabilityStarted(String invocationId,String agentName,String parentToolCallId,String snapshotId,String action,CapabilityDescriptor descriptor,Map<String,Object> arguments,long startedAt) {
        if(invocationId==null||invocationId.isBlank())return "";String id=UUID.randomUUID().toString();String resourcePath=Objects.toString(arguments.get("resourcePath"),"");Map<String,Object> metadata=descriptorMap(descriptor),safeArguments=safeMap(arguments);
        Map<String,Object> event=new LinkedHashMap<>(metadata);event.put("event",action);event.put("executionId",id);event.put("snapshotId",snapshotId);event.put("resourcePath",resourcePath);event.put("arguments",safeArguments);event.put("status","RUNNING");event.put("timestamp",startedAt);ensure(invocationId).capabilityEvents.add(event);runtimeEvent(invocationId,"CAPABILITY_"+action+"_STARTED",event);
        String runId=resolveActiveRun(invocationId,agentName,"");persist(()->persistence.capabilityExecutionStarted(id,invocationId,runId,agentName,parentToolCallId,snapshotId,action,metadata,resourcePath,safeArguments,startedAt));return id;
    }

    public void capabilityCompleted(String invocationId,String executionId,String action,CapabilityDescriptor descriptor,long startedAt,boolean success,Object result,Throwable error){
        if(invocationId==null||invocationId.isBlank()||executionId==null||executionId.isBlank())return;long completedAt=System.currentTimeMillis(),duration=Math.max(0,completedAt-startedAt);String raw=result==null?"":com.alibaba.fastjson.JSON.toJSONString(result),summary=truncate(raw,1000),hash=sha256(raw),status=success?"SUCCESS":"ERROR",message=error==null?"":truncate(error.getMessage(),2000);Map<String,Object> event=new LinkedHashMap<>(descriptorMap(descriptor));event.put("event",action);event.put("executionId",executionId);event.put("status",status);event.put("durationMs",duration);event.put("resultSize",raw.length());event.put("resultHash",hash);event.put("resultSummary",summary);event.put("error",message);event.put("timestamp",completedAt);ensure(invocationId).capabilityEvents.add(event);runtimeEvent(invocationId,"CAPABILITY_"+action+"_COMPLETED",event);persist(()->persistence.capabilityExecutionCompleted(executionId,status,completedAt,duration,summary,raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,hash,0,message));
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : order) {
            InvocationRecord record = records.get(id);
            if (record != null) result.add(record.toMap(false));
        }
        return result;
    }
    public synchronized List<Map<String,Object>> list(String userId){return list().stream().filter(item->Objects.equals(userId,item.get("userId"))).toList();}

    public Map<String, Object> detail(String id) {
        InvocationRecord record = records.get(id);
        return record == null ? Collections.emptyMap() : record.detailSnapshot();
    }
    public Map<String,Object> detail(String id,String userId){InvocationRecord record=records.get(id);return record==null||!Objects.equals(userId,record.userId)?Collections.emptyMap():record.detailSnapshot();}

    public Map<String, Object> summary() {
        long success = records.values().stream().filter(it -> "SUCCESS".equals(it.status)).count();
        long errors = records.values().stream().filter(it -> "ERROR".equals(it.status)).count();
        long active = records.values().stream().filter(it -> "RUNNING".equals(it.status)).count();
        long totalDuration = records.values().stream().mapToLong(InvocationRecord::durationMs).sum();
        long inputTokens = records.values().stream().mapToLong(it -> it.inputTokens.get()).sum();
        long outputTokens = records.values().stream().mapToLong(it -> it.outputTokens.get()).sum();
        return Map.of("total", records.size(), "success", success, "errors", errors, "active", active,
                "averageDurationMs", records.isEmpty() ? 0 : totalDuration / records.size(),
                "inputTokens", inputTokens, "outputTokens", outputTokens, "totalTokens", inputTokens + outputTokens,
                "registeredTools", new ArrayList<>(registeredTools));
    }
    public Map<String,Object> summary(String userId){List<InvocationRecord> scoped=records.values().stream().filter(r->Objects.equals(userId,r.userId)).toList();long success=scoped.stream().filter(it->"SUCCESS".equals(it.status)).count(),errors=scoped.stream().filter(it->"ERROR".equals(it.status)).count(),active=scoped.stream().filter(it->"RUNNING".equals(it.status)).count(),duration=scoped.stream().mapToLong(InvocationRecord::durationMs).sum(),input=scoped.stream().mapToLong(it->it.inputTokens.get()).sum(),output=scoped.stream().mapToLong(it->it.outputTokens.get()).sum();return Map.of("total",scoped.size(),"success",success,"errors",errors,"active",active,"averageDurationMs",scoped.isEmpty()?0:duration/scoped.size(),"inputTokens",input,"outputTokens",output,"totalTokens",input+output,"registeredTools",new ArrayList<>(registeredTools));}

    private InvocationRecord ensure(String id) {
        if (!records.containsKey(id)) runStarted(id, "", "", "unknown");
        return records.get(id);
    }

    public String resolveActiveRun(String invocationId,String agentName,String branch){
        if(agentName==null||agentName.isBlank())return null;
        RunHandle exact=activeRuns.get(runKey(invocationId,agentName,branch));
        if(exact!=null)return exact.runId;
        return activeRuns.values().stream()
                .filter(run->run.invocationId.equals(invocationId)&&run.agentName.equals(agentName))
                .max(Comparator.comparingLong(run->run.startedAt)).map(run->run.runId).orElse(null);
    }
    private static String runKey(String invocation,String agent,String branch){return invocation+'|'+normalizeBranch(branch)+'|'+Objects.toString(agent,"");}
    private static String normalizeBranch(String branch){return branch==null?"":branch;}

    private record RunHandle(String runId,String parentRunId,String invocationId,String agentName,String branch,long startedAt){}

    private void persist(Runnable action){if(persistence==null)return;try{action.run();}catch(Exception error){log.warn("Agent 观测数据持久化失败，主流程继续",error);}}

    private void runtimeEvent(String invocationId,String type,Map<String,Object> payload){InvocationRecord r=records.get(invocationId);if(r!=null&&!r.sessionId.isBlank())persist(()->persistence.runtimeEvent(r.sessionId,invocationId,type,payload));}
    private static Map<String,Object> descriptorMap(CapabilityDescriptor d){Map<String,Object> m=new LinkedHashMap<>();m.put("capabilityId",d.capabilityId());m.put("capabilityType",d.type());m.put("capabilityGroup",d.group());m.put("capabilityName",d.name());m.put("capabilityVersion",d.version());m.put("schemaVersion",d.schemaVersion());m.put("contentVersion",d.contentVersion());m.put("riskLevel",d.riskLevel());return m;}
    @SuppressWarnings("unchecked") private static Map<String,Object> safeMap(Map<String,Object> value){return (Map<String,Object>)redact(value);}
    public Map<String,Object> redactToolResult(Map<String,Object> value){return safeMap(value);}
    private static String sha256(String value){try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(bytes);}catch(Exception ignored){return "";}}

    private static Object redact(Object value){
        if(value instanceof Map<?,?> map){Map<String,Object> safe=new LinkedHashMap<>();map.forEach((key,item)->{String name=String.valueOf(key);safe.put(name,name.matches("(?i).*(password|passwd|secret|token|api.?key|authorization|cookie).*")?"***":redact(item));});return safe;}
        if(value instanceof Collection<?> values)return values.stream().map(LightweightMonitorService::redact).toList();
        String text=String.valueOf(value);return text.length()>2000?text.substring(0,2000)+"…":value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
    private static String redactText(String value){if(value==null)return "";return value.replaceAll("(?i)(password|passwd|secret|token|api[_-]?key|authorization|cookie)(\\s*[:=]\\s*)[^,}\\s]+","$1$2***");}

    private static final class InvocationRecord {
        final String invocationId;
        volatile String sessionId, userId, rootAgent, workflowName;
        final long startedAt = System.currentTimeMillis();
        volatile long completedAt;
        volatile String status = "RUNNING", error = "";
        final AtomicLong eventCount = new AtomicLong();
        final AtomicLong inputTokens = new AtomicLong(), outputTokens = new AtomicLong(), totalTokens = new AtomicLong();
        final Map<String, AgentRecord> agents = new ConcurrentHashMap<>();
        final Map<String, ToolRecord> tools = new ConcurrentHashMap<>();
        final List<Map<String, Object>> compressions = Collections.synchronizedList(new ArrayList<>());
        final List<Map<String, Object>> modelDecisions = Collections.synchronizedList(new ArrayList<>());
        final List<Map<String, Object>> capabilityEvents = Collections.synchronizedList(new ArrayList<>());
        InvocationRecord(String id, String session, String user, String root) { this(id, session, user, root, root); }
        InvocationRecord(String id, String session, String user, String root, String appName) {
            invocationId = id; sessionId = session; userId = user; rootAgent = root; workflowName = (appName == null || appName.isBlank()) ? root : appName;
        }
        void updateMetadata(String session, String user, String root, String appName) {
            if (session != null && !session.isBlank()) this.sessionId = session;
            if (user != null && !user.isBlank()) this.userId = user;
            if (root != null && !root.isBlank() && !"unknown".equalsIgnoreCase(root)) this.rootAgent = root;
            if (appName != null && !appName.isBlank() && !"unknown".equalsIgnoreCase(appName)) this.workflowName = appName;
            else if (root != null && !root.isBlank() && !"unknown".equalsIgnoreCase(root)) this.workflowName = root;
        }
        long durationMs() { return (completedAt > 0 ? completedAt : System.currentTimeMillis()) - startedAt; }
        Map<String,Object> toMap(boolean detail) {
            Map<String,Object> map = new LinkedHashMap<>();
            map.put("invocationId", invocationId); map.put("sessionId", sessionId); map.put("userId", userId);
            map.put("rootAgent", rootAgent); map.put("workflowName", workflowName); map.put("status", status); map.put("startedAt", startedAt);
            map.put("completedAt", completedAt); map.put("durationMs", durationMs()); map.put("eventCount", eventCount.get());
            map.put("agentCount", agents.size()); map.put("toolCount", tools.size()); map.put("compressionCount", compressions.size());
            map.put("inputTokens", inputTokens.get()); map.put("outputTokens", outputTokens.get()); map.put("totalTokens", totalTokens.get());
            map.put("tokensEstimated", agents.values().stream().anyMatch(agent -> agent.tokensEstimated));
            if (!error.isEmpty()) map.put("error", error);
            return map;
        }
        Map<String,Object> detailSnapshot() {
            Map<String,Object> map = toMap(false);
            List<Map<String,Object>> agentSnapshots = new ArrayList<>();
            for (AgentRecord agent : new ArrayList<>(agents.values())) agentSnapshots.add(agent.toMap());
            List<Map<String,Object>> toolSnapshots = new ArrayList<>();
            for (ToolRecord tool : new ArrayList<>(tools.values())) toolSnapshots.add(tool.toMap());
            List<Map<String,Object>> compressionSnapshots;
            synchronized (compressions) { compressionSnapshots = new ArrayList<>(compressions); }
            List<Map<String,Object>> modelDecisionSnapshots;
            synchronized (modelDecisions) { modelDecisionSnapshots = new ArrayList<>(modelDecisions); }
            List<Map<String,Object>> capabilityEventSnapshots;
            synchronized (capabilityEvents) { capabilityEventSnapshots = new ArrayList<>(capabilityEvents); }
            map.put("agents", agentSnapshots);
            map.put("tools", toolSnapshots);
            map.put("compressions", compressionSnapshots);
            map.put("modelDecisions", modelDecisionSnapshots);
            map.put("capabilityEvents", capabilityEventSnapshots);
            return map;
        }
        void recalculateTokens() { inputTokens.set(agents.values().stream().mapToLong(a -> a.inputTokens.get()).sum()); outputTokens.set(agents.values().stream().mapToLong(a -> a.outputTokens.get()).sum()); totalTokens.set(agents.values().stream().mapToLong(a -> a.totalTokens.get()).sum()); }
    }

    private static final class AgentRecord {
        final String name; volatile String lastRoutedModel = ""; volatile boolean tokensEstimated=true, providerUsageSeen=false; final AtomicLong startedAt=new AtomicLong(), completedAt=new AtomicLong(), modelStartedAt=new AtomicLong(), modelDurationMs=new AtomicLong(), modelCalls=new AtomicLong(), inputTokens=new AtomicLong(), outputTokens=new AtomicLong(), totalTokens=new AtomicLong();
        AgentRecord(String name){this.name=name;}
        void finishModel(long now){ long start=modelStartedAt.getAndSet(0); if(start>0) modelDurationMs.addAndGet(now-start); }
        void forceComplete(long now){ completedAt.compareAndSet(0,now); finishModel(now); }
        Map<String,Object> toMap(){ long end=completedAt.get()>0?completedAt.get():System.currentTimeMillis(); Map<String,Object> m=new LinkedHashMap<>(); m.put("name",name);m.put("startedAt",startedAt.get());m.put("completedAt",completedAt.get());m.put("durationMs",startedAt.get()>0?end-startedAt.get():0);m.put("modelCalls",modelCalls.get());m.put("modelDurationMs",modelDurationMs.get());m.put("inputTokens",inputTokens.get());m.put("outputTokens",outputTokens.get());m.put("totalTokens",totalTokens.get());m.put("tokensEstimated",tokensEstimated);return m; }
    }

    private static final class ToolRecord {
        final String callId, agentName, toolName; final long startedAt=System.currentTimeMillis(); volatile long completedAt; volatile String status="RUNNING", summary="";
        ToolRecord(String callId,String agentName,String toolName){this.callId=callId;this.agentName=agentName;this.toolName=toolName;}
        Map<String,Object> toMap(){ return Map.of("callId",callId,"agentName",agentName,"toolName",toolName,"startedAt",startedAt,"completedAt",completedAt,"durationMs",(completedAt>0?completedAt:System.currentTimeMillis())-startedAt,"status",status,"summary",summary); }
    }
}
