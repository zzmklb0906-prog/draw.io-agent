package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.domain.agent.service.orchestration.DynamicSubagentService;
import cn.bugstack.ai.domain.agent.service.monitor.InvocationVersionCatalog;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.events.Event;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service("lightweightMonitoringPlugin")
public class LightweightMonitoringPlugin extends BasePlugin {
    private final LightweightMonitorService monitor;
    private final DynamicSubagentService dynamicSubagents;
    private final InvocationVersionCatalog versions;
    public LightweightMonitoringPlugin(LightweightMonitorService monitor,DynamicSubagentService dynamicSubagents,InvocationVersionCatalog versions) { super("LightweightMonitoringPlugin"); this.monitor = monitor;this.dynamicSubagents=dynamicSubagents;this.versions=versions; }

    @Override public Maybe<com.google.genai.types.Content> beforeRunCallback(InvocationContext c) {
        monitor.runStarted(c.invocationId(), c.session().id(), c.userId(), c.agent().name(), c.appName());monitor.versionSnapshot(c.invocationId(),versions.snapshot(c.appName())); return Maybe.empty();
    }
    @Override public Completable afterRunCallback(InvocationContext c) {
        dynamicSubagents.awaitInvocation(c.invocationId(),300);
        monitor.runCompleted(c.invocationId(), true, ""); return Completable.complete();
    }
    @Override public Maybe<Event> onEventCallback(InvocationContext c, Event event) {
        monitor.eventSeen(c.invocationId());
        event.actions().compaction().ifPresent(compaction -> {
            int after = Math.max(1, compaction.compactedContent().toString().length() / 4);
            monitor.compression(c.invocationId(), 89_600, after,
                    "ADK_LLM_EVENT_SUMMARIZER", 0L);
        });
        return Maybe.empty();
    }
    @Override public Maybe<com.google.genai.types.Content> beforeAgentCallback(BaseAgent a, CallbackContext c) {
        String branch=c.branch().orElse("");
        String parent=a.parentAgent()==null?null:a.parentAgent().name();
        String forced=string(c.invocationContext().callbackContextData().get("monitor.forcedParentRunId."+branch));
        String runId=monitor.agentStarted(c.invocationId(),a.name(),parent,branch,forced);
        c.invocationContext().callbackContextData().put(runKey(branch,a.name()),runId);
        return Maybe.empty();
    }
    @Override public Maybe<com.google.genai.types.Content> afterAgentCallback(BaseAgent a, CallbackContext c) {
        String branch=c.branch().orElse("");
        String runId=string(c.invocationContext().callbackContextData().remove(runKey(branch,a.name())));
        if(runId!=null)c.invocationContext().callbackContextData().put("monitor.completedRun."+branch,runId);
        monitor.agentCompleted(c.invocationId(),a.name(),runId,branch); return Maybe.empty();
    }
    @Override public Maybe<LlmResponse> beforeModelCallback(CallbackContext c, LlmRequest.Builder b) {
        monitor.modelStarted(c.invocationId(), c.agentName(),currentRun(c));
        monitor.estimatedInput(c.invocationId(), c.agentName(), Math.max(1, b.build().contents().toString().length() / 4));
        return Maybe.empty();
    }
    @Override public Maybe<LlmResponse> afterModelCallback(CallbackContext c, LlmResponse r) {
        r.usageMetadata().ifPresent(usage -> monitor.usage(
                c.invocationId(), c.agentName(),
                usage.promptTokenCount().orElse(0),
                usage.candidatesTokenCount().orElse(0),
                usage.totalTokenCount().orElse(0)));
        if (r.usageMetadata().isEmpty()) {
            r.content().ifPresent(content -> monitor.estimatedOutput(c.invocationId(), c.agentName(), Math.max(1, content.toString().length() / 4)));
        }
        monitor.modelCompleted(c.invocationId(), c.agentName(),currentRun(c));
        return Maybe.empty();
    }
    @Override public Maybe<LlmResponse> onModelErrorCallback(CallbackContext c, LlmRequest.Builder b, Throwable error) {
        monitor.modelFailed(c.invocationId(), c.agentName(), currentRun(c),error);
        // ADK may fail before emitting the first Event and does not guarantee afterRun on this path.
        // Close the root observation here; runCompleted is idempotent if a later callback also arrives.
        monitor.runCompleted(c.invocationId(), false, error == null ? "Model call failed" : error.getMessage());
        return Maybe.empty();
    }
    @Override public Maybe<Map<String,Object>> beforeToolCallback(BaseTool tool, Map<String,Object> args, ToolContext c) {
        String id=c.functionCallId().orElseGet(()->java.util.UUID.randomUUID().toString()); c.functionCallId(id); monitor.toolStarted(c.invocationId(),c.agentName(),currentRun(c),id,tool.name(),args,tool.customMetadata()); return Maybe.empty();
    }
    @Override public Maybe<Map<String,Object>> afterToolCallback(BaseTool tool, Map<String,Object> args, ToolContext c, Map<String,Object> result) {
        Map<String,Object> safeResult=monitor.redactToolResult(result);String raw=com.alibaba.fastjson.JSON.toJSONString(safeResult);
        String artifactId=monitor.toolCompleted(c.invocationId(),c.functionCallId().orElse(""),true,raw);
        if(artifactId==null||artifactId.isBlank())return Maybe.just(safeResult);
        String summary=raw.length()<=1200?raw:raw.substring(0,1200)+"…[externalized]";
        return Maybe.just(Map.of("summary",summary,"artifactId",artifactId,"externalized",true,
                "message","完整 Tool Result 已保存为 Artifact；后续需要时通过 Artifact ID 获取。"));
    }
    @Override public Maybe<Map<String,Object>> onToolErrorCallback(BaseTool tool, Map<String,Object> args, ToolContext c, Throwable error) {
        monitor.toolCompleted(c.invocationId(),c.functionCallId().orElse(""),false,error==null?"Tool execution failed":String.valueOf(error.getMessage())); return Maybe.empty();
    }
    private String currentRun(CallbackContext c){String branch=c.branch().orElse("");String run=string(c.invocationContext().callbackContextData().get(runKey(branch,c.agentName())));return run==null?monitor.resolveActiveRun(c.invocationId(),c.agentName(),branch):run;}
    private static String runKey(String branch,String agent){return "monitor.run."+branch+'.'+agent;}
    private static String string(Object value){return value==null?null:String.valueOf(value);}
}
