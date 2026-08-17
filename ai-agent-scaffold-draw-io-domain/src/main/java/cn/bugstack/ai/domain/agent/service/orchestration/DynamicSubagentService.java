package cn.bugstack.ai.domain.agent.service.orchestration;

import cn.bugstack.ai.domain.agent.adapter.repository.IDynamicSubagentRepository;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import com.alibaba.fastjson.JSON;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;

/** Runtime control plane for model-selected ADK subagents. */
@Service
public class DynamicSubagentService {
    private final IDynamicSubagentRepository repository;
    private final LightweightMonitorService monitor;
    private final CapabilityRegistryService capabilities;
    private final ExecutorService executor=Executors.newFixedThreadPool(Math.max(2,Math.min(8,Runtime.getRuntime().availableProcessors())));
    private final Map<String,RuntimeSpec> runtimes=new ConcurrentHashMap<>();
    private final Map<String,CompletableFuture<Map<String,Object>>> futures=new ConcurrentHashMap<>();
    private final Map<String,TaskOwner> owners=new ConcurrentHashMap<>();
    private final Map<String,AtomicInteger> invocationTaskCounts=new ConcurrentHashMap<>();
    private final Map<String,AtomicInteger> invocationReservedTokens=new ConcurrentHashMap<>();
    private final Map<String,Semaphore> invocationConcurrency=new ConcurrentHashMap<>();
    @Value("${ai.agent.subagent.max-task-chars:65536}")
    private int maxTaskChars;
    @Value("${ai.agent.subagent.max-depth:3}") private int maxDepth;
    @Value("${ai.agent.subagent.max-tasks-per-invocation:8}") private int maxTasksPerInvocation;
    @Value("${ai.agent.subagent.max-concurrency:4}") private int maxConcurrency;
    @Value("${ai.agent.subagent.total-token-budget:200000}") private int totalTokenBudget;

    public DynamicSubagentService(IDynamicSubagentRepository repository,LightweightMonitorService monitor,CapabilityRegistryService capabilities){this.repository=repository;this.monitor=monitor;this.capabilities=capabilities;}
    public void registerRuntime(String agentName,BaseLlm model,List<Object> inheritedTools){runtimes.put(agentName,new RuntimeSpec(model,List.copyOf(inheritedTools)));}
    public List<Map<String,Object>> templates(){return repository.templates();}

    public Map<String,Object> spawn(String templateKey,String task,ToolContext context){return spawn(templateKey,task,List.of(),context);}
    public Map<String,Object> spawn(String templateKey,String task,List<String> dependencies,ToolContext context){
        if(task==null||task.isBlank())throw new IllegalArgumentException("task is required");
        if(task.length()>maxTaskChars)throw new IllegalArgumentException("task exceeds configured limit of "+maxTaskChars+" characters");
        Map<String,Object> template=repository.template(templateKey);
        if(template.isEmpty())throw new IllegalArgumentException("Unknown or disabled subagent template: "+templateKey);
        RuntimeSpec runtime=runtimes.get(context.agentName());
        if(runtime==null)throw new IllegalStateException("No dynamic subagent runtime registered for "+context.agentName());
        if(invocationTaskCounts.computeIfAbsent(context.invocationId(),ignored->new AtomicInteger()).incrementAndGet()>Math.max(1,maxTasksPerInvocation)){invocationTaskCounts.get(context.invocationId()).decrementAndGet();throw new IllegalStateException("An invocation may create at most "+maxTasksPerInvocation+" dynamic Subagents");}
        String branch=context.branch().orElse("");
        int depth=(int)Arrays.stream(branch.split("/")).filter("dynamic"::equals).count()+1;if(depth>Math.max(1,maxDepth)){invocationTaskCounts.get(context.invocationId()).decrementAndGet();throw new IllegalStateException("Dynamic Subagent depth exceeds "+maxDepth);}
        String parentTask=parentTask(branch);for(String dependency:dependencies){TaskOwner dependencyOwner=owners.get(dependency);if(dependencyOwner==null||!dependencyOwner.invocationId.equals(context.invocationId()))throw new SecurityException("Dependency does not belong to this invocation: "+dependency);if(dependency.equals(parentTask))throw new IllegalArgumentException("Subagent DAG cycle detected");}
        String parentRun=monitor.resolveActiveRun(context.invocationId(),context.agentName(),branch);
        long tokenBudget=template.get("tokenBudget") instanceof Number n?n.longValue():50_000L;
        if(tokenBudget<1||tokenBudget>Integer.MAX_VALUE){invocationTaskCounts.get(context.invocationId()).decrementAndGet();throw new IllegalStateException("Invalid Subagent token budget: "+tokenBudget);}
        int reserved=invocationReservedTokens.computeIfAbsent(context.invocationId(),ignored->new AtomicInteger()).addAndGet((int)tokenBudget);
        if(reserved>Math.max(1,totalTokenBudget)){
            invocationReservedTokens.get(context.invocationId()).addAndGet(-(int)tokenBudget);
            invocationTaskCounts.get(context.invocationId()).decrementAndGet();
            throw new IllegalStateException("Invocation Subagent token budget exceeds "+totalTokenBudget);
        }
        String taskId;
        try{taskId=repository.createTask(context.invocationId(),parentRun,parentTask,depth,dependencies,templateKey,context.userId(),task,tokenBudget);}
        catch(RuntimeException error){invocationReservedTokens.get(context.invocationId()).addAndGet(-(int)tokenBudget);invocationTaskCounts.get(context.invocationId()).decrementAndGet();throw error;}
        owners.put(taskId,new TaskOwner(context.invocationId(),context.userId()));
        CompletableFuture<Map<String,Object>> future=CompletableFuture.supplyAsync(()->execute(taskId,parentRun,template,task,dependencies,context,runtime),executor);
        futures.put(taskId,future);
        monitor.registerTool("spawn_subagent");monitor.registerTool("await_subagent");monitor.registerTool("list_subagent_templates");
        return Map.of("taskId",taskId,"status","PENDING","templateKey",templateKey,"message","Subagent task accepted; call await_subagent to collect its result.");
    }

    public Map<String,Object> await(String taskId,int timeoutSeconds,ToolContext context){
        TaskOwner owner=owners.get(taskId);
        if(owner!=null&&(!owner.invocationId.equals(context.invocationId())||!owner.userId.equals(context.userId())))throw new SecurityException("Subagent task does not belong to this invocation");
        CompletableFuture<Map<String,Object>> future=futures.get(taskId);
        if(future==null)return Map.of("taskId",taskId,"status","UNKNOWN","error","Task is not present in this application instance.");
        try{return future.get(Math.max(1,Math.min(timeoutSeconds,300)),TimeUnit.SECONDS);}
        catch(TimeoutException e){return Map.of("taskId",taskId,"status","RUNNING");}
        catch(Exception e){return Map.of("taskId",taskId,"status","ERROR","error",message(e));}
    }
    /** Keeps invocation completion behind all accepted children, even if the model forgot to await one. */
    public void awaitInvocation(String invocationId,int timeoutSeconds){
        Map<String,CompletableFuture<Map<String,Object>>> pending=new LinkedHashMap<>();owners.forEach((task,owner)->{CompletableFuture<Map<String,Object>> future=futures.get(task);if(owner.invocationId.equals(invocationId)&&future!=null)pending.put(task,future);});
        if(pending.isEmpty())return;
        try{CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new)).get(Math.max(1,timeoutSeconds),TimeUnit.SECONDS);}
        catch(Exception timeout){pending.forEach((task,future)->{if(!future.isDone()){future.cancel(true);repository.failTask(task,"","Parent invocation stopped waiting for Subagent after "+Math.max(1,timeoutSeconds)+" seconds");}});}
        finally{owners.entrySet().removeIf(e->{if(!e.getValue().invocationId.equals(invocationId))return false;futures.remove(e.getKey());return true;});invocationTaskCounts.remove(invocationId);invocationReservedTokens.remove(invocationId);invocationConcurrency.remove(invocationId);}
    }

    @PreDestroy public void shutdown(){executor.shutdownNow();}

    private Map<String,Object> execute(String taskId,String parentRun,Map<String,Object> template,String task,List<String> dependencies,ToolContext toolContext,RuntimeSpec runtime){
        for(String dependency:dependencies){try{Map<String,Object> result=futures.get(dependency).get();if(!"SUCCESS".equals(result.get("status")))throw new IllegalStateException("Dependency failed: "+dependency);}catch(Exception error){repository.failTask(taskId,"",message(error));return Map.of("taskId",taskId,"status","ERROR","error","Dependency failed: "+dependency);}}
        Semaphore concurrency=invocationConcurrency.computeIfAbsent(toolContext.invocationId(),ignored->new Semaphore(Math.max(1,maxConcurrency)));
        boolean acquired=false;
        try{concurrency.acquire();acquired=true;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();repository.failTask(taskId,"","Interrupted while waiting for Subagent execution slot");return Map.of("taskId",taskId,"status","ERROR","error","Interrupted while waiting for execution slot");}
        repository.startTask(taskId);
        String templateKey=String.valueOf(template.get("templateKey"));
        String branch="dynamic/"+taskId;
        String agentName="dynamic_"+templateKey.replaceAll("[^A-Za-z0-9_]","_")+"_"+taskId.substring(0,8).replace('-','_');
        List<String> groups=parseGroups(template.get("capabilityGroups"));
        capabilities.allowAgentPolicy(agentName,groups,Objects.toString(template.get("permissionMode"),"READ_ONLY"));
        LlmAgent child=LlmAgent.builder().name(agentName).description(String.valueOf(template.get("description")))
                .model(runtime.model).instruction(String.valueOf(template.get("instruction")))
                .tools(runtime.inheritedTools).maxSteps(((Number)template.get("maxSteps")).intValue())
                .disallowTransferToParent(true).disallowTransferToPeers(true).build();
        runtimes.put(agentName,runtime);
        InvocationContext parent=toolContext.invocationContext();
        parent.callbackContextData().put("monitor.forcedParentRunId."+branch,parentRun==null?"":parentRun);
        InvocationContext childContext=parent.toBuilder().agent(child).branch(branch)
                .userContent(Content.builder().role("user").parts(List.of(Part.fromText(task))).build()).build();
        List<Event> observedEvents=Collections.synchronizedList(new ArrayList<>());
        try{
            List<Event> events=child.runAsync(childContext).doOnNext(observedEvents::add).timeout(((Number)template.get("timeoutSeconds")).longValue(),TimeUnit.SECONDS).toList().blockingGet();
            String result=events.stream().filter(Event::finalResponse).map(Event::stringifyContent).filter(v->v!=null&&!v.isBlank()).reduce((a,b)->b).orElseGet(()->events.stream().map(Event::stringifyContent).filter(v->v!=null&&!v.isBlank()).reduce("",String::concat));
            String childRun=Objects.toString(childContext.callbackContextData().get("monitor.completedRun."+branch),"");
            boolean withinBudget=repository.completeTaskWithinBudget(taskId,childRun,result);
            return withinBudget?Map.of("taskId",taskId,"status","SUCCESS","templateKey",templateKey,"childRunId",childRun,"result",result):Map.of("taskId",taskId,"status","ERROR","templateKey",templateKey,"childRunId",childRun,"error","Subagent token budget exceeded");
        }catch(Throwable error){
            String childRun=Objects.toString(childContext.callbackContextData().get("monitor.completedRun."+branch),"");
            String partial=observedEvents.stream().map(Event::stringifyContent).filter(v->v!=null&&!v.isBlank()).reduce("",String::concat);
            String failure=message(error)+(partial.isBlank()?"":"; partial result preserved");
            repository.failTask(taskId,childRun,failure,partial);
            Map<String,Object> response=new LinkedHashMap<>();response.put("taskId",taskId);response.put("status","ERROR");response.put("templateKey",templateKey);response.put("childRunId",childRun);response.put("error",failure);if(!partial.isBlank())response.put("partialResult",partial);return response;
        }finally{if(acquired)concurrency.release();runtimes.remove(agentName);capabilities.removeAgentPolicy(agentName);parent.callbackContextData().remove("monitor.forcedParentRunId."+branch);childContext.callbackContextData().remove("monitor.forcedParentRunId."+branch);try{child.close().blockingAwait();}catch(Exception ignored){}}
    }
    private static String message(Throwable error){Throwable cause=error instanceof ExecutionException&&error.getCause()!=null?error.getCause():error;String value=cause.getMessage();return value==null?cause.getClass().getSimpleName():value.substring(0,Math.min(2000,value.length()));}
    private static List<String> parseGroups(Object raw){try{return JSON.parseArray(Objects.toString(raw,"[]"),String.class).stream().filter(v->v!=null&&!v.isBlank()).toList();}catch(Exception ignored){return List.of();}}
    private static String parentTask(String branch){String[] parts=branch.split("/");for(int i=parts.length-2;i>=0;i--)if("dynamic".equals(parts[i])&&i+1<parts.length)return parts[i+1];return null;}
    private record RuntimeSpec(BaseLlm model,List<Object> inheritedTools){}
    private record TaskOwner(String invocationId,String userId){}
}
