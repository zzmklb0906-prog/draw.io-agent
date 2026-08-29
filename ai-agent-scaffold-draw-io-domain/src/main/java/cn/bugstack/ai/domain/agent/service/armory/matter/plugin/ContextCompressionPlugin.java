package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import cn.bugstack.ai.domain.agent.adapter.repository.IContextSnapshotRepository;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.ContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.HeuristicContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 四层上下文模型防护与压缩插件。
 * <p>
 * 分层职责：
 * <ul>
 *   <li>Layer 1 稳定规则：Agent 指令、Tool Schema 与 Skill 目录，属于 Runner/Agent 固有装配，不写入压缩 Envelope。</li>
 *   <li>Layer 2 结构化状态：确定性白名单过滤的业务会话状态，以及从 Draw.io 输出提取的节点、连线、样式和几何布局。</li>
 *   <li>Layer 3 活跃对话：保留最近完整对话轮次，维持 Tool Call / Tool Response 配对边界，不出现孤立响应。</li>
 *   <li>Layer 4 外部观测：Tool/MCP 结果进行软截断限额，保留工具名、callId、摘要、指纹以及关联的 Artifact 引用。</li>
 * </ul>
 * </p>
 */
@Service("contextCompressionPlugin")
public class ContextCompressionPlugin extends BasePlugin {

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int DEFAULT_FALLBACK_CONTEXT_WINDOW = 128_000;

    // 70% 水位通常由 Runner 的 ADK LlmEventSummarizer 在会话层触发；此处作为请求级安全防护。
    public static final double COMPRESS_AT = 0.85;
    public static final double AGGRESSIVE_AT = 0.90;
    public static final double REJECT_AT = 0.95;

    /**
     * 确定性白名单：仅提取对工作流与绘图项目有业务价值的状态字段，默认排除密码、秘钥等未识别敏感数据。
     */
    public static final Set<String> ALLOWED_SESSION_KEYS = Set.of(
            "analysis_result",
            "draft_diagram",
            "final_result",
            "task_goal",
            "diagram_type",
            "workflow_stage",
            "workflow_status",
            "approval_result",
            "approval_json",
            "capability.snapshotId",
            "capability.snapshotIds",
            "current_artifact_id",
            "current_artifact",
            "tool_result_artifacts"
    );

    private final LightweightMonitorService monitor;
    private final ContextTokenEstimator tokenEstimator;
    private final ModelCatalogService modelCatalogService;
    private IContextSnapshotRepository snapshots;

    @Autowired
    public ContextCompressionPlugin(LightweightMonitorService monitor,
                                    ContextTokenEstimator tokenEstimator,
                                    @Autowired(required = false) ModelCatalogService modelCatalogService) {
        super("ContextCompressionPlugin");
        this.monitor = monitor;
        this.tokenEstimator = tokenEstimator != null ? tokenEstimator : new HeuristicContextTokenEstimator(new LatestUserMessageExtractor());
        this.modelCatalogService = modelCatalogService;
    }

    @Autowired(required = false)
    public void setSnapshots(IContextSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder builder) {
        long started = System.currentTimeMillis();
        LlmRequest request = builder.build();
        List<Content> contents = request.contents();
        if (contents == null || contents.isEmpty()) {
            return Maybe.empty();
        }

        long contextWindow = resolveContextWindow(request);
        int estimated = (int) tokenEstimator.estimate(request);
        double ratio = estimated / (double) contextWindow;

        // < 85%：未达压缩水位，放行原始请求
        if (ratio < COMPRESS_AT) {
            return Maybe.empty();
        }

        boolean aggressive = ratio >= AGGRESSIVE_AT;
        int keep = aggressive ? 6 : 12;
        int initialCut = Math.max(0, contents.size() - keep);
        int cut = adjustCutForToolBoundaries(contents, initialCut);

        int maxToolChars = aggressive ? 1600 : 4000;
        List<Content> recent = new ArrayList<>();
        for (Content content : contents.subList(cut, contents.size())) {
            recent.add(trimToolResult(content, maxToolChars));
        }

        Map<String, Object> sessionState = (context != null && context.state() != null) ? context.state() : Collections.emptyMap();
        Map<String, Object> projectState = structuredProjectState(sessionState);
        String invocationId = context != null ? context.invocationId() : "inv-default";
        if (snapshots != null) {
            projectState = snapshots.enrichForInvocation(invocationId, projectState);
        }
        if (context != null && context.state() != null) {
            try {
                context.state().put("diagram_project_state", projectState);
            } catch (UnsupportedOperationException ignored) {
            }
        }

        String envelope = buildEnvelope(sessionState, projectState, contents.subList(0, cut), aggressive);
        recent.add(0, Content.fromParts(Part.fromText(envelope)));
        builder.contents(recent);

        LlmRequest compactedRequest = builder.build();
        int after = (int) tokenEstimator.estimate(compactedRequest);
        if (after / (double) contextWindow >= REJECT_AT) {
            throw new IllegalStateException("上下文已超过模型窗口的 95%，请新建会话或先执行上下文压缩");
        }

        String strategy = aggressive
                ? "AGGRESSIVE_WINDOW+STRUCTURED_STATE+TOOL_RESULT_TRIM"
                : "SLIDING_WINDOW+STRUCTURED_STATE+EXTRACTIVE_SUMMARY";

        String modelName = request.model().filter(m -> !m.isBlank()).orElse("agent-default");
        long duration = System.currentTimeMillis() - started;
        if (monitor != null) {
            monitor.compression(invocationId, estimated, after, strategy, duration);
        }
        if (snapshots != null) {
            snapshots.saveForInvocation(invocationId, envelope, projectState, estimated, after, strategy, modelName, duration);
        }

        return Maybe.empty();
    }

    /**
     * 动态解析模型上下文窗口：若模型已在目录注册则读取配置上限，否则回退保守的 128K 窗口。
     */
    public long resolveContextWindow(LlmRequest request) {
        if (request == null) {
            return DEFAULT_FALLBACK_CONTEXT_WINDOW;
        }
        String modelName = request.model().orElse("").trim();
        if (modelName.isEmpty() || modelCatalogService == null) {
            return DEFAULT_FALLBACK_CONTEXT_WINDOW;
        }
        Optional<ModelProfile> profile = modelCatalogService.findByModelName(modelName);
        if (profile.isEmpty()) {
            profile = modelCatalogService.findById(modelName);
        }
        if (profile.isPresent() && profile.get().limits() != null && profile.get().limits().contextWindowTokens() > 0) {
            return profile.get().limits().contextWindowTokens();
        }
        return DEFAULT_FALLBACK_CONTEXT_WINDOW;
    }

    /**
     * 维护 Tool Call / Tool Response 配对边界：
     * 避免因滑动窗口盲目切分导致 tool_response 孤立存在（引发 LLM API 协议校验错误）。
     * 不向前历史回溯，遇到孤立响应时后移切点将其剔除，并重新求值防止连锁孤立响应。
     */
    public static int adjustCutForToolBoundaries(List<Content> contents, int initialCut) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }
        int cut = Math.max(0, Math.min(initialCut, contents.size()));

        while (cut < contents.size()) {
            Set<String> callIds = new HashSet<>();
            Set<String> callNames = new HashSet<>();
            int orphanIndex = -1;

            for (int i = cut; i < contents.size(); i++) {
                Content content = contents.get(i);
                collectCallIdentities(content, callIds, callNames);
                if (hasOrphanResponse(content, callIds, callNames)) {
                    orphanIndex = i;
                    break;
                }
            }

            if (orphanIndex >= 0) {
                cut = orphanIndex + 1;
            } else {
                break;
            }
        }

        return cut;
    }

    static void collectCallIdentities(Content content, Set<String> callIds, Set<String> callNames) {
        if (content == null || content.parts().isEmpty()) return;
        for (Part part : content.parts().get()) {
            if (part == null) continue;
            part.functionCall().ifPresent(fc -> {
                fc.id().filter(id -> !id.isBlank()).ifPresent(callIds::add);
                fc.name().filter(name -> !name.isBlank()).ifPresent(callNames::add);
            });
            part.toolCall().flatMap(tc -> tc.id().filter(id -> !id.isBlank())).ifPresent(callIds::add);
        }
    }

    static boolean hasOrphanResponse(Content content, Set<String> callIds, Set<String> callNames) {
        if (content == null || content.parts().isEmpty()) return false;
        for (Part part : content.parts().get()) {
            if (part == null) continue;
            if (part.functionResponse().isPresent()) {
                FunctionResponse fr = part.functionResponse().get();
                String id = fr.id().filter(s -> !s.isBlank()).orElse(null);
                String name = fr.name().filter(s -> !s.isBlank()).orElse(null);
                if (id != null) {
                    if (!callIds.contains(id)) {
                        return true;
                    }
                } else if (name != null) {
                    if (!callNames.contains(name)) {
                        return true;
                    }
                }
            }
            if (part.toolResponse().isPresent()) {
                String id = part.toolResponse().flatMap(tr -> tr.id().filter(s -> !s.isBlank())).orElse(null);
                if (id != null && !callIds.contains(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Layer 4: Tool/MCP 输出单独限额并保持结构化协议完整。
     * 超长 FunctionResponse 替换为紧凑占位符，保留 toolName、callId、摘要、指纹以及现存 Artifact 引用。
     * 若非支持安全重构的类型，保留原始协议对象，不破坏协议完整性。
     */
    public static Content trimToolResult(Content content, int maxChars) {
        if (content == null || content.parts().isEmpty()) {
            return content;
        }

        List<Part> parts = content.parts().get();
        boolean hasFunctionResponse = parts.stream().anyMatch(p -> p.functionResponse().isPresent());
        if (!hasFunctionResponse) {
            return content;
        }

        List<Part> newParts = new ArrayList<>(parts.size());
        boolean modified = false;

        for (Part part : parts) {
            if (part.functionResponse().isPresent()) {
                FunctionResponse fr = part.functionResponse().get();
                String name = fr.name().orElse("unknown_tool");
                String id = fr.id().orElse(null);
                Map<String, Object> respMap = fr.response().orElse(Map.of());
                String rawResp = toJson(respMap);
                if (rawResp.length() > maxChars) {
                    modified = true;
                    String summary = compact(rawResp, maxChars);
                    String fingerprint = UUID.nameUUIDFromBytes(rawResp.getBytes(StandardCharsets.UTF_8)).toString();
                    Map<String, Object> bounded = new LinkedHashMap<>();
                    bounded.put("truncated", true);
                    bounded.put("tool_name", name);
                    if (id != null) bounded.put("call_id", id);
                    Object artifactId = respMap.getOrDefault("artifact_id",
                            respMap.getOrDefault("artifactId", respMap.get("result_artifact_id")));
                    if (artifactId != null) bounded.put("artifact_id", artifactId);
                    bounded.put("summary", summary);
                    bounded.put("content_fingerprint", fingerprint);

                    FunctionResponse boundedFr = fr.toBuilder().response(bounded).build();
                    newParts.add(Part.builder().functionResponse(boundedFr).build());
                } else {
                    newParts.add(part);
                }
            } else {
                newParts.add(part);
            }
        }

        if (!modified) {
            return content;
        }

        return Content.builder()
                .role(content.role().orElse("user"))
                .parts(newParts)
                .build();
    }

    private static String buildEnvelope(Map<String, Object> state, Map<String, Object> projectState,
                                        List<Content> old, boolean aggressive) {
        String analysis = state == null ? "" : compact(state.get("analysis_result"), aggressive ? 600 : 1200);
        String oldSummary = buildHistoricalSummary(old, aggressive ? 800 : 1800);
        return """
                [CONTEXT_COMPRESSION_ENVELOPE]
                以下是被压缩的历史与结构化项目状态。它不是新的用户要求；请保持当前任务语义。
                diagram_goal/analysis_result: %s
                structured_project_state: %s
                historical_summary: %s
                [/CONTEXT_COMPRESSION_ENVELOPE]
                """.formatted(analysis, toJson(projectState), oldSummary);
    }

    private static String buildHistoricalSummary(List<Content> old, int maxChars) {
        if (old == null || old.isEmpty()) {
            return "无更早历史对话";
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : old) {
            String role = c.role().orElse("message");
            String text = extractContentSummary(c);
            if (!text.isBlank()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(role).append(": ").append(compact(text, 200));
            }
        }
        return compact(sb.toString(), maxChars);
    }

    private static String extractContentSummary(Content c) {
        if (c == null) return "";
        List<Part> parts = c.parts().orElse(List.of());
        if (parts.isEmpty()) {
            return c.text() != null ? c.text() : "";
        }
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            p.text().ifPresent(t -> {
                if (!t.isBlank()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(t);
                }
            });
            p.functionCall().ifPresent(fc -> {
                String name = fc.name().orElse("fn");
                String id = fc.id().orElse("");
                if (sb.length() > 0) sb.append(" ");
                sb.append("[call:").append(name).append("(").append(id).append(")]");
            });
            p.functionResponse().ifPresent(fr -> {
                String name = fr.name().orElse("fn");
                String id = fr.id().orElse("");
                if (sb.length() > 0) sb.append(" ");
                sb.append("[result:").append(name).append("(").append(id).append(")]");
            });
        }
        return sb.toString();
    }

    /**
     * Layer 2: 确定性业务状态与 Draw.io 结构化事实提取。
     * 保留节点/连线几何坐标与样式约束，但不保留膨胀的原始 XML 字符串。
     */
    public static Map<String, Object> structuredProjectState(Map<String, Object> state) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("schema_version", "four-tier-v1");

        String analysis = compact(state == null ? null : state.get("analysis_result"), 1200);
        project.put("task_goal", analysis);

        Map<String, Object> activeState = new LinkedHashMap<>();
        if (state != null) {
            for (Map.Entry<String, Object> e : state.entrySet()) {
                String key = e.getKey();
                if (ALLOWED_SESSION_KEYS.contains(key) && !"diagram_project_state".equals(key)) {
                    activeState.put(key, compact(e.getValue(), 600));
                }
            }
        }
        project.put("active_agent_state", activeState);

        String source = state == null ? "" : String.valueOf(state.getOrDefault("final_result",
                state.getOrDefault("draft_diagram", "")));

        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        Map<String, Map<String, Object>> edgesById = new LinkedHashMap<>();

        extractDrawioFromSource(source, nodesById, edgesById);

        if (!nodesById.isEmpty() || !edgesById.isEmpty()) {
            project.put("artifact_type", "drawio");
            project.put("diagram_type", inferDiagramType(analysis));
            project.put("nodes", new ArrayList<>(nodesById.values()));
            project.put("edges", new ArrayList<>(edgesById.values()));
            project.put("constraints", Map.of("layout", "preserve-current", "theme", "preserve-current"));
        } else {
            project.put("artifact_type", "generic");
        }

        project.put("content_fingerprint", UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString());
        return project;
    }

    private static void extractDrawioFromSource(String source,
                                                Map<String, Map<String, Object>> nodesById,
                                                Map<String, Map<String, Object>> edgesById) {
        if (source == null || source.isBlank()) return;

        // 逐行解析 NDJSON，兼顾流式输出格式
        for (String line : source.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.startsWith("{") || !candidate.endsWith("}")) continue;
            try {
                JsonNode item = JSON.readTree(candidate);
                String type = item.path("type").asText();
                if ("drawio_node".equals(type)) {
                    String id = item.path("id").asText();
                    String label = item.path("label").asText();
                    String xml = item.path("xml").asText();
                    Map<String, Object> node = new LinkedHashMap<>();
                    if (!id.isBlank()) node.put("id", id);
                    if (!label.isBlank()) node.put("label", label);
                    parseNodeXml(xml, node);
                    if (node.containsKey("id") && !String.valueOf(node.get("id")).isBlank()) {
                        nodesById.put(String.valueOf(node.get("id")), node);
                    }
                } else if ("drawio_edge".equals(type)) {
                    String id = item.path("id").asText();
                    String label = item.path("label").asText();
                    String sourceId = item.path("source").asText();
                    String targetId = item.path("target").asText();
                    String xml = item.path("xml").asText();
                    Map<String, Object> edge = new LinkedHashMap<>();
                    if (!id.isBlank()) edge.put("id", id);
                    if (!label.isBlank()) edge.put("label", label);
                    if (!sourceId.isBlank()) edge.put("source", sourceId);
                    if (!targetId.isBlank()) edge.put("target", targetId);
                    parseEdgeXml(xml, edge);
                    if (edge.containsKey("id") && !String.valueOf(edge.get("id")).isBlank()) {
                        edgesById.put(String.valueOf(edge.get("id")), edge);
                    }
                } else if ("drawio_done".equals(type)) {
                    String content = item.path("content").asText();
                    if (nodesById.isEmpty() && edgesById.isEmpty() && content.contains("<mxCell")) {
                        parseFullXml(content, nodesById, edgesById);
                    }
                }
            } catch (Exception ignored) {
                // 忽略残缺流式片段，绝不影响同批次其他合法元素
            }
        }

        // 若 NDJSON 未提供节点但整体内容为标准 XML，全量解析 XML
        if (nodesById.isEmpty() && edgesById.isEmpty() && source.contains("<mxCell")) {
            parseFullXml(source, nodesById, edgesById);
        }
    }

    private static void parseFullXml(String xml,
                                     Map<String, Map<String, Object>> nodesById,
                                     Map<String, Map<String, Object>> edgesById) {
        if (xml == null || xml.isBlank()) return;
        try {
            DocumentBuilder db = createSafeDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(xml)));
            NodeList cellList = doc.getElementsByTagName("mxCell");
            for (int i = 0; i < cellList.getLength(); i++) {
                if (cellList.item(i) instanceof Element cell) {
                    String id = cell.getAttribute("id");
                    if ("0".equals(id) || "1".equals(id)) continue;
                    boolean isEdge = "1".equals(cell.getAttribute("edge"))
                            || (cell.hasAttribute("source") && !cell.getAttribute("source").isBlank())
                            || (cell.hasAttribute("target") && !cell.getAttribute("target").isBlank());
                    if (isEdge) {
                        Map<String, Object> edge = new LinkedHashMap<>();
                        if (!id.isBlank()) edge.put("id", id);
                        populateEdgeFromCell(cell, edge);
                        if (!id.isBlank()) edgesById.put(id, edge);
                    } else {
                        Map<String, Object> node = new LinkedHashMap<>();
                        if (!id.isBlank()) node.put("id", id);
                        populateNodeFromCell(cell, node);
                        if (!id.isBlank()) nodesById.put(id, node);
                    }
                }
            }
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Failed to configure secure XML parser for Draw.io extraction", e);
        } catch (Exception ignored) {
            // 忽略残缺片段
        }
    }

    private static Element parseCellElement(String xml) {
        if (xml == null || xml.isBlank()) return null;
        try {
            DocumentBuilder db = createSafeDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            return "mxCell".equalsIgnoreCase(root.getTagName()) ? root : findFirstElement(root, "mxCell");
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Failed to configure secure XML parser for Draw.io extraction", e);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void parseNodeXml(String xml, Map<String, Object> nodeMap) {
        Element cell = parseCellElement(xml);
        if (cell != null) {
            populateNodeFromCell(cell, nodeMap);
        }
    }

    private static void populateNodeFromCell(Element cell, Map<String, Object> nodeMap) {
        if (cell.hasAttribute("id") && !cell.getAttribute("id").isBlank() && !nodeMap.containsKey("id")) {
            nodeMap.put("id", cell.getAttribute("id"));
        }
        if (cell.hasAttribute("parent") && !cell.getAttribute("parent").isBlank()) {
            nodeMap.putIfAbsent("parent", cell.getAttribute("parent"));
        }
        if (cell.hasAttribute("style") && !cell.getAttribute("style").isBlank()) {
            nodeMap.putIfAbsent("style", cell.getAttribute("style"));
        }
        if (cell.hasAttribute("value") && !cell.getAttribute("value").isBlank() && !nodeMap.containsKey("label")) {
            nodeMap.put("label", cell.getAttribute("value"));
        }
        Element geo = findFirstChildElement(cell, "mxGeometry");
        if (geo != null) {
            Map<String, Object> geometry = new LinkedHashMap<>();
            if (geo.hasAttribute("x")) geometry.put("x", parseCoordinate(geo.getAttribute("x")));
            if (geo.hasAttribute("y")) geometry.put("y", parseCoordinate(geo.getAttribute("y")));
            if (geo.hasAttribute("width")) geometry.put("width", parseCoordinate(geo.getAttribute("width")));
            if (geo.hasAttribute("height")) geometry.put("height", parseCoordinate(geo.getAttribute("height")));
            if (geo.hasAttribute("relative") && !geo.getAttribute("relative").isBlank()) {
                geometry.put("relative", geo.getAttribute("relative"));
            }
            if (!geometry.isEmpty()) {
                nodeMap.put("geometry", geometry);
            }
        }
    }

    private static void parseEdgeXml(String xml, Map<String, Object> edgeMap) {
        Element cell = parseCellElement(xml);
        if (cell != null) {
            populateEdgeFromCell(cell, edgeMap);
        }
    }

    private static void populateEdgeFromCell(Element cell, Map<String, Object> edgeMap) {
        if (cell.hasAttribute("id") && !cell.getAttribute("id").isBlank() && !edgeMap.containsKey("id")) {
            edgeMap.put("id", cell.getAttribute("id"));
        }
        if (cell.hasAttribute("source") && !cell.getAttribute("source").isBlank()) {
            edgeMap.putIfAbsent("source", cell.getAttribute("source"));
        }
        if (cell.hasAttribute("target") && !cell.getAttribute("target").isBlank()) {
            edgeMap.putIfAbsent("target", cell.getAttribute("target"));
        }
        if (cell.hasAttribute("parent") && !cell.getAttribute("parent").isBlank()) {
            edgeMap.putIfAbsent("parent", cell.getAttribute("parent"));
        }
        if (cell.hasAttribute("style") && !cell.getAttribute("style").isBlank()) {
            edgeMap.putIfAbsent("style", cell.getAttribute("style"));
        }
        if (cell.hasAttribute("value") && !cell.getAttribute("value").isBlank() && !edgeMap.containsKey("label")) {
            edgeMap.put("label", cell.getAttribute("value"));
        }
        Element geo = findFirstChildElement(cell, "mxGeometry");
        if (geo != null) {
            Map<String, Object> geometry = new LinkedHashMap<>();
            if (geo.hasAttribute("relative") && !geo.getAttribute("relative").isBlank()) {
                geometry.put("relative", geo.getAttribute("relative"));
            }
            List<Map<String, Object>> points = new ArrayList<>();
            NodeList children = geo.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element child) {
                    if ("mxPoint".equalsIgnoreCase(child.getTagName())) {
                        String as = child.getAttribute("as");
                        Map<String, Object> pt = new LinkedHashMap<>();
                        if (child.hasAttribute("x")) pt.put("x", parseCoordinate(child.getAttribute("x")));
                        if (child.hasAttribute("y")) pt.put("y", parseCoordinate(child.getAttribute("y")));
                        if ("sourcePoint".equalsIgnoreCase(as)) {
                            geometry.put("sourcePoint", pt);
                        } else if ("targetPoint".equalsIgnoreCase(as)) {
                            geometry.put("targetPoint", pt);
                        } else {
                            points.add(pt);
                        }
                    } else if ("Array".equalsIgnoreCase(child.getTagName())) {
                        NodeList arrayChildren = child.getChildNodes();
                        for (int j = 0; j < arrayChildren.getLength(); j++) {
                            if (arrayChildren.item(j) instanceof Element arrayChild) {
                                if ("mxPoint".equalsIgnoreCase(arrayChild.getTagName())) {
                                    Map<String, Object> pt = new LinkedHashMap<>();
                                    if (arrayChild.hasAttribute("x")) pt.put("x", parseCoordinate(arrayChild.getAttribute("x")));
                                    if (arrayChild.hasAttribute("y")) pt.put("y", parseCoordinate(arrayChild.getAttribute("y")));
                                    points.add(pt);
                                }
                            }
                        }
                    }
                }
            }
            if (!points.isEmpty()) {
                geometry.put("points", points);
                edgeMap.put("points", points);
            }
            if (!geometry.isEmpty()) {
                edgeMap.put("geometry", geometry);
            }
        }
    }

    /**
     * 遵循 OWASP 安全规范创建禁用外部实体解析（XXE 防护）的 DocumentBuilder。
     * 若关键安全特性设置失败，绝不吞没异常并退化为不安全解析，直接抛出 ParserConfigurationException。
     */
    private static DocumentBuilder createSafeDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static Element findFirstElement(Element root, String tagName) {
        if (root == null) return null;
        NodeList list = root.getElementsByTagName(tagName);
        return list.getLength() > 0 ? (Element) list.item(0) : null;
    }

    private static Element findFirstChildElement(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e && tagName.equalsIgnoreCase(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private static Object parseCoordinate(String str) {
        if (str == null || str.isBlank()) return 0;
        try {
            if (str.contains(".")) {
                return Double.parseDouble(str);
            }
            return Long.parseLong(str);
        } catch (Exception e) {
            return str;
        }
    }

    private static String inferDiagramType(String text) {
        if (text == null) return "flowchart";
        if (text.contains("时序") || text.toLowerCase().contains("sequence")) return "sequence";
        if (text.contains("架构") || text.toLowerCase().contains("architecture")) return "architecture";
        if (text.contains("UML") || text.contains("类图")) return "uml";
        return "flowchart";
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            return "{}";
        }
    }

    private static String compact(Object value, int max) {
        String text = value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…[trimmed]";
    }
}
