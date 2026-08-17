package cn.bugstack.ai.domain.agent.service.orchestration;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.*;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DynamicSubagentToolset implements BaseToolset {
    private final DynamicSubagentService service;
    public DynamicSubagentToolset(DynamicSubagentService service){this.service=service;}
    @Override public Flowable<BaseTool> getTools(ReadonlyContext context){return Flowable.just(new ListTemplates(),new Spawn(),new Await());}
    @Override public void close(){}
    private final class ListTemplates extends Tool {ListTemplates(){super("list_subagent_templates","List approved runtime Subagent templates. Use before spawning when the best role is unclear.",schema(Map.of(),List.of()));}public Single<Map<String,Object>> runAsync(Map<String,Object>a,ToolContext c){return Single.just(Map.of("templates",service.templates()));}}
    private final class Spawn extends Tool {Spawn(){super("spawn_subagent","Create a constrained runtime ADK Subagent. Optional dependsOn is a comma-separated DAG dependency list from this invocation.",schema(Map.of("templateKey",str("Approved template key."),"task",str("Self-contained task, constraints, evidence requirements and output contract."),"dependsOn",str("Optional comma-separated task IDs that must succeed first.")),List.of("templateKey","task")));}public Single<Map<String,Object>> runAsync(Map<String,Object>a,ToolContext c){List<String> dependencies=java.util.Arrays.stream(String.valueOf(a.getOrDefault("dependsOn","")).split(",")).map(String::trim).filter(v->!v.isBlank()).distinct().toList();return Single.fromCallable(()->service.spawn(required(a,"templateKey"),required(a,"task"),dependencies,c));}}
    private final class Await extends Tool {Await(){super("await_subagent","Wait for a spawned Subagent and return its final result. A RUNNING response may be retried.",schema(Map.of("taskId",str("Task ID returned by spawn_subagent."),"timeoutSeconds",Schema.builder().type(Type.Known.INTEGER).minimum(1d).maximum(300d).description("Maximum wait for this call.").build()),List.of("taskId")));}public Single<Map<String,Object>> runAsync(Map<String,Object>a,ToolContext c){int timeout=a.get("timeoutSeconds") instanceof Number n?n.intValue():120;return Single.fromCallable(()->service.await(required(a,"taskId"),timeout,c));}}
    private abstract static class Tool extends BaseTool {private final FunctionDeclaration d;Tool(String n,String desc,Schema s){super(n,desc);d=FunctionDeclaration.builder().name(n).description(desc).parameters(s).build();}@Override public Optional<FunctionDeclaration> declaration(){return Optional.of(d);}}
    private static Schema schema(Map<String,Schema> p,List<String> r){return Schema.builder().type(Type.Known.OBJECT).properties(p).required(r).build();}
    private static Schema str(String d){return Schema.builder().type(Type.Known.STRING).description(d).build();}
    private static String required(Map<String,Object>a,String n){String v=Objects.toString(a.get(n),"").trim();if(v.isEmpty())throw new IllegalArgumentException(n+" is required");return v;}
}
