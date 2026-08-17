package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.domain.agent.adapter.repository.IContextSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lightweight context guard. Token counts are conservative estimates (roughly chars / 4).
 * It keeps recent conversational turns and injects a compact, structured project envelope.
 */
@Service("contextCompressionPlugin")
public class ContextCompressionPlugin extends BasePlugin {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONTEXT_WINDOW = 128_000;
    // 70% 由 Runner 的 ADK LlmEventSummarizer 处理；这里仅作为请求级二次保险。
    private static final double COMPRESS_AT = .85, AGGRESSIVE_AT = .90, REJECT_AT = .95;
    private final LightweightMonitorService monitor;
    private IContextSnapshotRepository snapshots;

    public ContextCompressionPlugin(LightweightMonitorService monitor) { super("ContextCompressionPlugin"); this.monitor = monitor; }
    @Autowired(required=false) public void setSnapshots(IContextSnapshotRepository snapshots){this.snapshots=snapshots;}

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder builder) {
        long started = System.currentTimeMillis();
        LlmRequest request = builder.build();
        List<Content> contents = request.contents();
        int estimated = estimate(contents);
        double ratio = estimated / (double) CONTEXT_WINDOW;
        if (ratio < COMPRESS_AT) return Maybe.empty();
        if (ratio >= REJECT_AT) {
            throw new IllegalStateException("上下文已超过模型窗口的 95%，请新建会话或先执行上下文压缩");
        }

        int keep = ratio >= AGGRESSIVE_AT ? 6 : 12;
        int cut = Math.max(0, contents.size() - keep);
        List<Content> recent = new ArrayList<>();
        for(Content content:contents.subList(cut,contents.size()))recent.add(trimToolResult(content,ratio>=AGGRESSIVE_AT?1600:4000));
        Map<String, Object> projectState = structuredProjectState(context.state());
        if(snapshots!=null)projectState=snapshots.enrichForInvocation(context.invocationId(),projectState);
        context.state().put("diagram_project_state", projectState);
        String envelope = buildEnvelope(context.state(), projectState, contents.subList(0, cut), ratio >= AGGRESSIVE_AT);
        recent.add(0, Content.fromParts(Part.fromText(envelope)));
        builder.contents(recent);
        int after = estimate(recent);
        String strategy = ratio >= AGGRESSIVE_AT
                ? "AGGRESSIVE_WINDOW+STRUCTURED_STATE+TOOL_RESULT_TRIM"
                : "SLIDING_WINDOW+STRUCTURED_STATE+EXTRACTIVE_SUMMARY";
        monitor.compression(context.invocationId(), estimated, after, strategy, System.currentTimeMillis() - started);
        if(snapshots!=null)snapshots.saveForInvocation(context.invocationId(),envelope,projectState,estimated,after,strategy,"agent-default",System.currentTimeMillis()-started);
        return Maybe.empty();
    }

    private static int estimate(List<Content> contents) {
        long chars = contents.stream().mapToLong(it -> String.valueOf(it).length()).sum();
        return (int) Math.max(1, chars / 4);
    }

    /** Tool/MCP 输出单独限额；稳定 Prompt、Tool Schema 与 Skill Catalog 不在 contents 历史窗口中。 */
    private static Content trimToolResult(Content content,int maxChars){
        if(content.parts().isEmpty())return content;boolean hasToolResult=content.parts().get().stream().anyMatch(part->part.functionResponse().isPresent()||part.toolResponse().isPresent());
        String raw=String.valueOf(content);if(!hasToolResult||raw.length()<=maxChars)return content;
        String summary=compact(raw,maxChars);return Content.builder().role(content.role().orElse("tool")).parts(List.of(Part.fromText("[TOOL_RESULT_SUMMARY] "+summary+" [/TOOL_RESULT_SUMMARY]"))).build();
    }

    private static String buildEnvelope(Map<String, Object> state, Map<String, Object> projectState,
                                        List<Content> old, boolean aggressive) {
        String analysis = compact(state.get("analysis_result"), aggressive ? 600 : 1200);
        String oldSummary = compact(old, aggressive ? 800 : 1800);
        return """
                [CONTEXT_COMPRESSION_ENVELOPE]
                以下是被压缩的历史与结构化项目状态。它不是新的用户要求；请保持当前任务语义。
                diagram_goal/analysis_result: %s
                structured_project_state: %s
                historical_summary: %s
                [/CONTEXT_COMPRESSION_ENVELOPE]
                """.formatted(analysis, toJson(projectState), oldSummary);
    }

    private static Map<String, Object> structuredProjectState(Map<String, Object> state) {
        Map<String, Object> project = new LinkedHashMap<>();
        String analysis = compact(state.get("analysis_result"), 1200);
        String source = String.valueOf(state.getOrDefault("final_result",
                state.getOrDefault("draft_diagram", "")));
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (String line : source.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.startsWith("{") || !candidate.endsWith("}")) continue;
            try {
                JsonNode item = JSON.readTree(candidate);
                String type = item.path("type").asText();
                if ("drawio_node".equals(type)) {
                    nodes.add(Map.of("id", item.path("id").asText(), "label", item.path("label").asText(),
                            "xml", compact(item.path("xml").asText(), 800)));
                } else if ("drawio_edge".equals(type)) {
                    edges.add(Map.of("id", item.path("id").asText(), "label", item.path("label").asText(),
                            "source", item.path("source").asText(), "target", item.path("target").asText()));
                }
            } catch (Exception ignored) {
                // Partial streaming fragments are intentionally ignored.
            }
        }
        project.put("task_goal", analysis);
        project.put("active_agent_state", state.entrySet().stream().filter(e->!String.valueOf(e.getKey()).toLowerCase().contains("key")).limit(24).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->compact(e.getValue(),600),(a,b)->a,LinkedHashMap::new)));
        if(!nodes.isEmpty()||!edges.isEmpty()){
            project.put("artifact_type","drawio");project.put("diagram_type",inferDiagramType(analysis));project.put("nodes",nodes);project.put("edges",edges);project.put("constraints",Map.of("layout","preserve-current","theme","preserve-current"));
        }else project.put("artifact_type","generic");
        project.put("content_fingerprint", UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString());
        return project;
    }

    private static String inferDiagramType(String text) {
        if (text.contains("时序") || text.toLowerCase().contains("sequence")) return "sequence";
        if (text.contains("架构") || text.toLowerCase().contains("architecture")) return "architecture";
        if (text.contains("UML") || text.contains("类图")) return "uml";
        return "flowchart";
    }

    private static String toJson(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { return "{}"; }
    }

    private static String compact(Object value, int max) {
        String text = value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…[trimmed]";
    }
}
