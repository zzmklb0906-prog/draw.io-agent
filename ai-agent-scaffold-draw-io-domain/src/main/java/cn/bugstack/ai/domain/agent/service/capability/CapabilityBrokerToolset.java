package cn.bugstack.ai.domain.agent.service.capability;

import cn.bugstack.ai.domain.agent.model.valobj.CapabilityDescriptor;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.springframework.stereotype.Component;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.domain.identity.adapter.ISecurityAuditRepository;

import java.util.*;

/** The only stable toolset required by a general-purpose agent. */
@Component
public class CapabilityBrokerToolset implements BaseToolset {
    private final CapabilityRegistryService registry;
    private final LightweightMonitorService monitor;
    private final ISecurityAuditRepository audit;

    public CapabilityBrokerToolset(CapabilityRegistryService registry, LightweightMonitorService monitor,ISecurityAuditRepository audit) { this.registry = registry; this.monitor = monitor;this.audit=audit; }

    @Override public Flowable<BaseTool> getTools(ReadonlyContext context) {
        return Flowable.just(new SearchTool(), new LoadTool(), new ExecuteTool());
    }
    @Override public void close() { }

    private final class SearchTool extends DeclaredTool {
        SearchTool() { super("search_capabilities", "Search the runtime capability registry and create an invocation-scoped Top-K snapshot.", objectSchema(Map.of(
                "query", stringSchema("Natural-language description of the required capability."),
                "types", stringSchema("Optional comma-separated types such as SKILL,MCP_TOOL,JAVA_TOOL."),
                "limit", integerSchema("Maximum results, from 1 to 16.")), List.of("query"))); }
        @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) {
            long startedAt=System.currentTimeMillis();
            String query = Objects.toString(args.get("query"), "");
            List<String> types = Arrays.stream(Objects.toString(args.get("types"), "").split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
            int limit = args.get("limit") instanceof Number number ? number.intValue() : 8;
            var result = registry.search(context.invocationId(), context.userId(), context.agentName(), query, types, limit);
            context.state().put("capability.snapshotId", result.snapshotId());
            context.state().put("capability.snapshotIds", result.capabilities().stream().map(item -> item.capability().capabilityId()).toList());
            List<Map<String,Object>> items = result.capabilities().stream().map(item -> {
                CapabilityDescriptor d = item.capability();
                Map<String,Object> map = new LinkedHashMap<>();
                map.put("capabilityId", d.capabilityId()); map.put("type", d.type()); map.put("name", d.name());
                map.put("description", d.description()); map.put("group", d.group()); map.put("riskLevel", d.riskLevel()); map.put("version",d.version());map.put("schemaVersion",d.schemaVersion());map.put("contentVersion",d.contentVersion()); map.put("score", item.score());
                return map;
            }).toList();
            monitor.capabilitySearch(context.invocationId(),context.agentName(),context.functionCallId().orElse(""),result.snapshotId(),query,types,items,result.registrySize(),startedAt);
            return Single.just(Map.of("snapshotId", result.snapshotId(), "items", items, "registrySize", result.registrySize()));
        }
    }

    private final class LoadTool extends DeclaredTool {
        LoadTool() { super("load_capability", "Load the full metadata and input schema of one capability from a search snapshot.", objectSchema(Map.of(
                "snapshotId", stringSchema("Snapshot returned by search_capabilities."),
                "capabilityId", stringSchema("Selected capability ID.")), List.of("snapshotId", "capabilityId"))); }
        @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) {
            long startedAt=System.currentTimeMillis();
            restoreFromSession(args, context);
            CapabilityDescriptor d = registry.load(required(args,"snapshotId"), required(args,"capabilityId"), context);
            String executionId=monitor.capabilityStarted(context.invocationId(),context.agentName(),context.functionCallId().orElse(""),required(args,"snapshotId"),"LOAD",d,Map.of(),startedAt);
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("capabilityId", d.capabilityId()); result.put("type", d.type()); result.put("name", d.name());
            result.put("description", d.description()); result.put("group", d.group()); result.put("riskLevel", d.riskLevel());
            result.put("inputSchema", d.inputSchema()); result.put("version", d.version());result.put("schemaVersion",d.schemaVersion());result.put("contentVersion",d.contentVersion());
            monitor.capabilityCompleted(context.invocationId(),executionId,"LOAD",d,startedAt,true,result,null);
            return Single.just(result);
        }
    }

    private final class ExecuteTool extends DeclaredTool {
        ExecuteTool() { super("execute_capability", "Execute a capability selected from an invocation-scoped snapshot. Load its schema first.", objectSchema(Map.of(
                "snapshotId", stringSchema("Snapshot returned by search_capabilities."),
                "capabilityId", stringSchema("Selected capability ID."),
                "arguments", Schema.builder().type(Type.Known.OBJECT).description("Arguments matching the loaded capability schema. For a Skill, optionally pass resourcePath.").build()),
                List.of("snapshotId", "capabilityId", "arguments"))); }
        @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) {
            restoreFromSession(args, context);
            Object raw = args.get("arguments");
            Map<String,Object> arguments = raw instanceof Map<?,?> map ? toStringMap(map) : Map.of();
            String snapshotId=required(args,"snapshotId"), capabilityId=required(args,"capabilityId");
            CapabilityDescriptor descriptor=registry.load(snapshotId,capabilityId,context);
            if (!"READ_ONLY".equalsIgnoreCase(descriptor.riskLevel())) {
                if (context.toolConfirmation().isEmpty()) {
                    audit.record(context.userId(),"TOOL_APPROVAL_REQUESTED","CAPABILITY",capabilityId,"PENDING","",Map.of("riskLevel",descriptor.riskLevel(),"invocationId",context.invocationId()));
                    context.requestConfirmation("确认执行动态能力 " + descriptor.name() + "（风险级别 " + descriptor.riskLevel() + "）", Map.of("capabilityId", capabilityId, "arguments", arguments));
                    return Single.just(Map.of("status","WAITING_CONFIRMATION","capabilityId",capabilityId,"riskLevel",descriptor.riskLevel()));
                }
                if (!context.toolConfirmation().get().confirmed()){audit.record(context.userId(),"TOOL_APPROVAL_DECIDED","CAPABILITY",capabilityId,"DENIED","",Map.of("riskLevel",descriptor.riskLevel(),"invocationId",context.invocationId()));return Single.just(Map.of("status","DENIED","capabilityId",capabilityId,"message","用户拒绝执行该能力，请说明影响或选择低风险替代方案。"));}
                audit.record(context.userId(),"TOOL_APPROVAL_DECIDED","CAPABILITY",capabilityId,"APPROVED","",Map.of("riskLevel",descriptor.riskLevel(),"invocationId",context.invocationId()));
            }
            long startedAt=System.currentTimeMillis();String executionId=monitor.capabilityStarted(context.invocationId(),context.agentName(),context.functionCallId().orElse(""),snapshotId,"EXECUTE",descriptor,arguments,startedAt);
            return registry.execute(snapshotId, capabilityId, arguments, context)
                    .doOnSuccess(result->{monitor.capabilityCompleted(context.invocationId(),executionId,"EXECUTE",descriptor,startedAt,true,result,null);if(!"READ_ONLY".equalsIgnoreCase(descriptor.riskLevel()))audit.record(context.userId(),"TOOL_EXECUTED","CAPABILITY",capabilityId,"SUCCESS","",Map.of("riskLevel",descriptor.riskLevel(),"invocationId",context.invocationId()));})
                    .doOnError(error->{monitor.capabilityCompleted(context.invocationId(),executionId,"EXECUTE",descriptor,startedAt,false,null,error);if(!"READ_ONLY".equalsIgnoreCase(descriptor.riskLevel()))audit.record(context.userId(),"TOOL_EXECUTED","CAPABILITY",capabilityId,"ERROR","",Map.of("riskLevel",descriptor.riskLevel(),"invocationId",context.invocationId(),"error",Objects.toString(error.getMessage(),"")));});
        }
    }

    private abstract static class DeclaredTool extends BaseTool {
        private final FunctionDeclaration declaration;
        DeclaredTool(String name, String description, Schema schema) {
            super(name, description);
            this.declaration = FunctionDeclaration.builder().name(name).description(description).parameters(schema).build();
        }
        @Override public Optional<FunctionDeclaration> declaration() { return Optional.of(declaration); }
    }

    private static Schema objectSchema(Map<String,Schema> properties, List<String> required) { return Schema.builder().type(Type.Known.OBJECT).properties(properties).required(required).build(); }
    private static Schema stringSchema(String description) { return Schema.builder().type(Type.Known.STRING).description(description).build(); }
    private static Schema integerSchema(String description) { return Schema.builder().type(Type.Known.INTEGER).description(description).minimum(1d).maximum(16d).build(); }
    private static String required(Map<String,Object> args, String name) { String value=Objects.toString(args.get(name),"").trim(); if(value.isEmpty())throw new IllegalArgumentException(name+" is required");return value; }
    private static Map<String,Object> toStringMap(Map<?,?> source) { Map<String,Object> result=new LinkedHashMap<>();source.forEach((key,value)->result.put(String.valueOf(key),value));return result; }
    private void restoreFromSession(Map<String,Object> args, ToolContext context) {
        String snapshotId = Objects.toString(args.get("snapshotId"), "");
        if (!registry.snapshotCapabilities(snapshotId).isEmpty() || context == null) return;
        Object storedId = context.state().get("capability.snapshotId");
        Object storedCapabilities = context.state().get("capability.snapshotIds");
        if (snapshotId.equals(storedId) && storedCapabilities instanceof Collection<?> values) {
            registry.restoreSnapshot(snapshotId, context.invocationId(), context.userId(), context.agentName(), values.stream().map(String::valueOf).toList());
        }
    }
}
