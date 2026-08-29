package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IContextSnapshotRepository;
import cn.bugstack.ai.domain.agent.service.armory.matter.plugin.ContextCompressionPlugin;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelLimits;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.ContextTokenEstimator;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContextCompressionPlugin}.
 *
 * <p>Covers the core contract requirements:
 * <ol>
 *   <li>Registered model window and 128K fallback;</li>
 *   <li>Allowlisted state excludes unknown secret-like keys;</li>
 *   <li>Draw.io NDJSON and full XML preserve node/edge geometry and styles while tolerating malformed siblings;</li>
 *   <li>Oversized FunctionResponse remains a FunctionResponse and retains identity, truncation marker, fingerprint, and artifact reference;</li>
 *   <li>Tool boundary repair never moves backward, drops orphan responses, and handles chained orphans while preserving complete pairs;</li>
 *   <li>Raw context >= 95% enters aggressive compression and is accepted when compressed below 95%;</li>
 *   <li>Compressed request still >= 95% is rejected without persisting or monitoring.</li>
 * </ol>
 * </p>
 */
class ContextCompressionPluginTest {

    // =========================================================================
    // Check 1: Registered model window and 128K fallback
    // =========================================================================

    @Test
    @DisplayName("Check 1: Registered model window and 128K fallback")
    void check1_registeredModelWindowAnd128kFallback() {
        ModelCatalogService catalogService = Mockito.mock(ModelCatalogService.class);
        ModelLimits limits = new ModelLimits(200_000L, 8192L);
        ModelProfile registeredProfile = new ModelProfile(
                "gemini-2.5-pro",
                "google",
                "gemini-2.5-pro",
                true,
                null,
                null,
                limits,
                null
        );

        Mockito.when(catalogService.findByModelName("gemini-2.5-pro")).thenReturn(Optional.of(registeredProfile));
        Mockito.when(catalogService.findByModelName("model-by-id")).thenReturn(Optional.empty());
        Mockito.when(catalogService.findById("model-by-id")).thenReturn(Optional.of(registeredProfile));

        ContextCompressionPlugin pluginWithCatalog = new ContextCompressionPlugin(null, null, catalogService);
        ContextCompressionPlugin pluginWithoutCatalog = new ContextCompressionPlugin(null, null, null);

        // 1. Registered model by modelName returns registered context window
        LlmRequest reqByModelName = LlmRequest.builder().model("gemini-2.5-pro").build();
        assertEquals(200_000L, pluginWithCatalog.resolveContextWindow(reqByModelName),
                "Registered model must return its configured context window tokens");

        // 2. Registered model by id fallback returns registered context window
        LlmRequest reqById = LlmRequest.builder().model("model-by-id").build();
        assertEquals(200_000L, pluginWithCatalog.resolveContextWindow(reqById),
                "Model resolvable via ID index must return its configured context window tokens");

        // 3. Fallback: null request -> 128K
        assertEquals(ContextCompressionPlugin.DEFAULT_FALLBACK_CONTEXT_WINDOW,
                pluginWithCatalog.resolveContextWindow(null),
                "Null request must fall back to 128K context window");

        // 4. Fallback: empty or blank model name -> 128K
        LlmRequest reqEmptyModel = LlmRequest.builder().model("").build();
        assertEquals(128_000L, pluginWithCatalog.resolveContextWindow(reqEmptyModel),
                "Empty model name must fall back to 128K context window");

        // 5. Fallback: unknown / unregistered model -> 128K
        Mockito.when(catalogService.findByModelName("unknown-model")).thenReturn(Optional.empty());
        Mockito.when(catalogService.findById("unknown-model")).thenReturn(Optional.empty());
        LlmRequest reqUnknown = LlmRequest.builder().model("unknown-model").build();
        assertEquals(128_000L, pluginWithCatalog.resolveContextWindow(reqUnknown),
                "Unregistered model must fall back to 128K context window");

        // 6. Fallback: null catalog service -> 128K
        assertEquals(128_000L, pluginWithoutCatalog.resolveContextWindow(reqByModelName),
                "Plugin without model catalog service must fall back to 128K context window");

        // 7. Fallback: profile with non-positive context window limit -> 128K
        ModelProfile zeroLimitProfile = new ModelProfile(
                "zero-limit",
                "google",
                "zero-limit",
                true,
                null,
                null,
                new ModelLimits(0L, 0L),
                null
        );
        Mockito.when(catalogService.findByModelName("zero-limit")).thenReturn(Optional.of(zeroLimitProfile));
        LlmRequest reqZero = LlmRequest.builder().model("zero-limit").build();
        assertEquals(128_000L, pluginWithCatalog.resolveContextWindow(reqZero),
                "Model profile with zero context window limit must fall back to 128K context window");
    }

    // =========================================================================
    // Check 2: Allowlisted state excludes an unknown secret-like key
    // =========================================================================

    @Test
    @DisplayName("Check 2: Allowlisted state excludes an unknown secret-like key")
    void check2_allowlistedStateExcludesUnknownSecretLikeKey() {
        Map<String, Object> sessionState = new LinkedHashMap<>();
        // Allowlisted business fields
        sessionState.put("task_goal", "Design user login sequence diagram");
        sessionState.put("workflow_stage", "ANALYSIS");
        sessionState.put("workflow_status", "IN_PROGRESS");
        sessionState.put("analysis_result", "Detailed architectural breakdown");
        sessionState.put("current_artifact_id", "artifact-uuid-001");

        // Sensitive / unknown secret-like keys that must NOT be retained in structured state
        sessionState.put("api_key", "sk-proj-super-secret-token-12345");
        sessionState.put("password", "p@ssw0rdSecure!");
        sessionState.put("db_connection_secret", "postgres://user:pass@host:5432/db");
        sessionState.put("authorization_bearer", "Bearer eyJhbGciOiJIUzI1Ni...");
        sessionState.put("internal_signing_key", "secret-private-key-material");

        Map<String, Object> projectState = ContextCompressionPlugin.structuredProjectState(sessionState);
        assertNotNull(projectState, "Structured project state must not be null");

        @SuppressWarnings("unchecked")
        Map<String, Object> activeState = (Map<String, Object>) projectState.get("active_agent_state");
        assertNotNull(activeState, "active_agent_state must be present");

        // Verify allowlisted business keys are retained
        assertTrue(activeState.containsKey("task_goal"));
        assertEquals("Design user login sequence diagram", activeState.get("task_goal"));
        assertTrue(activeState.containsKey("workflow_stage"));
        assertEquals("ANALYSIS", activeState.get("workflow_stage"));
        assertTrue(activeState.containsKey("workflow_status"));
        assertEquals("IN_PROGRESS", activeState.get("workflow_status"));
        assertTrue(activeState.containsKey("analysis_result"));
        assertEquals("Detailed architectural breakdown", activeState.get("analysis_result"));
        assertTrue(activeState.containsKey("current_artifact_id"));
        assertEquals("artifact-uuid-001", activeState.get("current_artifact_id"));

        // Verify unknown secret-like keys are strictly excluded from active_agent_state
        assertFalse(activeState.containsKey("api_key"), "api_key must be excluded");
        assertFalse(activeState.containsKey("password"), "password must be excluded");
        assertFalse(activeState.containsKey("db_connection_secret"), "db_connection_secret must be excluded");
        assertFalse(activeState.containsKey("authorization_bearer"), "authorization_bearer must be excluded");
        assertFalse(activeState.containsKey("internal_signing_key"), "internal_signing_key must be excluded");

        // Verify unknown secret-like keys are NOT present anywhere in the top-level project state either
        assertFalse(projectState.containsKey("api_key"));
        assertFalse(projectState.containsKey("password"));
        assertFalse(projectState.containsKey("db_connection_secret"));
        assertFalse(projectState.containsKey("authorization_bearer"));
        assertFalse(projectState.containsKey("internal_signing_key"));
    }

    // =========================================================================
    // Check 3: Draw.io NDJSON/full XML preserves node/edge geometry and styles,
    // tolerating a malformed sibling
    // =========================================================================

    @Test
    @DisplayName("Check 3A: Draw.io NDJSON preserves node id/label/style/geometry and edge source/target/style/geometry, tolerating a malformed sibling")
    void check3a_drawioNdjsonPreservesNodeAndEdgeAttributesToleratingMalformedSibling() {
        String ndjsonWithMalformedSibling = """
                {"type":"drawio_node","id":"node_auth","label":"Auth Service","xml":"<mxCell id=\\"node_auth\\" value=\\"Auth Service\\" style=\\"rounded=1;fillColor=#dae8fc;strokeColor=#6c8ebf;\\" vertex=\\"1\\" parent=\\"1\\"><mxGeometry x=\\"150\\" y=\\"100\\" width=\\"120\\" height=\\"60\\" as=\\"geometry\\"/></mxCell>"}
                {CORRUPT_JSON_MALFORMED_SIBLING_PAYLOAD: unparseable line
                {"type":"drawio_node","id":"broken_xml_node","label":"Corrupted","xml":"<mxCell unclosed syntax"}
                {"type":"drawio_edge","id":"edge_login","label":"verifyCredentials","source":"node_client","target":"node_auth","xml":"<mxCell id=\\"edge_login\\" value=\\"verifyCredentials\\" style=\\"edgeStyle=orthogonalEdgeStyle;rounded=0;\\" edge=\\"1\\" parent=\\"1\\" source=\\"node_client\\" target=\\"node_auth\\"><mxGeometry relative=\\"1\\" as=\\"geometry\\"><mxPoint x=\\"80\\" y=\\"130\\" as=\\"sourcePoint\\"/><mxPoint x=\\"150\\" y=\\"130\\" as=\\"targetPoint\\"/></mxGeometry></mxCell>"}
                """;

        Map<String, Object> state = Map.of(
                "final_result", ndjsonWithMalformedSibling,
                "analysis_result", "User authentication architecture"
        );

        Map<String, Object> projectState = ContextCompressionPlugin.structuredProjectState(state);
        assertEquals("drawio", projectState.get("artifact_type"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) projectState.get("nodes");
        assertNotNull(nodes);
        assertFalse(nodes.isEmpty(), "Nodes must be extracted despite malformed sibling");

        Map<String, Object> authNode = nodes.stream()
                .filter(n -> "node_auth".equals(n.get("id")))
                .findFirst()
                .orElse(null);
        assertNotNull(authNode, "node_auth must be preserved");
        assertEquals("node_auth", authNode.get("id"));
        assertEquals("Auth Service", authNode.get("label"));
        assertEquals("rounded=1;fillColor=#dae8fc;strokeColor=#6c8ebf;", authNode.get("style"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nodeGeo = (Map<String, Object>) authNode.get("geometry");
        assertNotNull(nodeGeo, "Node geometry must be preserved");
        assertEquals(150L, ((Number) nodeGeo.get("x")).longValue());
        assertEquals(100L, ((Number) nodeGeo.get("y")).longValue());
        assertEquals(120L, ((Number) nodeGeo.get("width")).longValue());
        assertEquals(60L, ((Number) nodeGeo.get("height")).longValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) projectState.get("edges");
        assertNotNull(edges);
        assertFalse(edges.isEmpty(), "Edges must be extracted despite malformed sibling");

        Map<String, Object> loginEdge = edges.stream()
                .filter(e -> "edge_login".equals(e.get("id")))
                .findFirst()
                .orElse(null);
        assertNotNull(loginEdge, "edge_login must be preserved");
        assertEquals("edge_login", loginEdge.get("id"));
        assertEquals("verifyCredentials", loginEdge.get("label"));
        assertEquals("node_client", loginEdge.get("source"));
        assertEquals("node_auth", loginEdge.get("target"));
        assertEquals("edgeStyle=orthogonalEdgeStyle;rounded=0;", loginEdge.get("style"));

        @SuppressWarnings("unchecked")
        Map<String, Object> edgeGeo = (Map<String, Object>) loginEdge.get("geometry");
        assertNotNull(edgeGeo, "Edge geometry must be preserved");
        assertEquals("1", edgeGeo.get("relative"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sp = (Map<String, Object>) edgeGeo.get("sourcePoint");
        assertNotNull(sp, "Edge sourcePoint must be preserved");
        assertEquals(80L, ((Number) sp.get("x")).longValue());
        assertEquals(130L, ((Number) sp.get("y")).longValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> tp = (Map<String, Object>) edgeGeo.get("targetPoint");
        assertNotNull(tp, "Edge targetPoint must be preserved");
        assertEquals(150L, ((Number) tp.get("x")).longValue());
        assertEquals(130L, ((Number) tp.get("y")).longValue());
    }

    @Test
    @DisplayName("Check 3B: Draw.io full XML preserves node id/label/style/geometry and edge source/target/style/geometry, tolerating a malformed sibling")
    void check3b_drawioFullXmlPreservesNodeAndEdgeAttributesToleratingMalformedSibling() {
        String fullXmlWithMalformedSibling = """
                <mxGraphModel>
                  <root>
                    <mxCell id="0"/>
                    <mxCell id="1" parent="0"/>
                    <!-- Malformed/empty sibling cell lacking standard attributes -->
                    <mxCell id="empty_sibling"/>
                    <mxCell id="node_db" value="PostgreSQL DB" style="shape=cylinder3;whiteSpace=wrap;fillColor=#d5e8d4;" vertex="1" parent="1">
                      <mxGeometry x="320" y="240" width="90" height="100" as="geometry"/>
                    </mxCell>
                    <!-- Sibling with non-standard tag -->
                    <unknownTag attr="ignored"/>
                    <mxCell id="edge_query" value="executeSQL" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="node_auth" target="node_db">
                      <mxGeometry relative="1" as="geometry">
                        <mxPoint x="270" y="130" as="sourcePoint"/>
                        <mxPoint x="320" y="290" as="targetPoint"/>
                      </mxGeometry>
                    </mxCell>
                  </root>
                </mxGraphModel>
                """;

        Map<String, Object> state = Map.of(
                "draft_diagram", fullXmlWithMalformedSibling,
                "analysis_result", "Database architecture"
        );

        Map<String, Object> projectState = ContextCompressionPlugin.structuredProjectState(state);
        assertEquals("drawio", projectState.get("artifact_type"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) projectState.get("nodes");
        assertNotNull(nodes);

        Map<String, Object> dbNode = nodes.stream()
                .filter(n -> "node_db".equals(n.get("id")))
                .findFirst()
                .orElse(null);
        assertNotNull(dbNode, "node_db must be preserved from XML");
        assertEquals("node_db", dbNode.get("id"));
        assertEquals("PostgreSQL DB", dbNode.get("label"));
        assertEquals("shape=cylinder3;whiteSpace=wrap;fillColor=#d5e8d4;", dbNode.get("style"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nodeGeo = (Map<String, Object>) dbNode.get("geometry");
        assertNotNull(nodeGeo);
        assertEquals(320L, ((Number) nodeGeo.get("x")).longValue());
        assertEquals(240L, ((Number) nodeGeo.get("y")).longValue());
        assertEquals(90L, ((Number) nodeGeo.get("width")).longValue());
        assertEquals(100L, ((Number) nodeGeo.get("height")).longValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) projectState.get("edges");
        assertNotNull(edges);

        Map<String, Object> queryEdge = edges.stream()
                .filter(e -> "edge_query".equals(e.get("id")))
                .findFirst()
                .orElse(null);
        assertNotNull(queryEdge, "edge_query must be preserved from XML");
        assertEquals("edge_query", queryEdge.get("id"));
        assertEquals("executeSQL", queryEdge.get("label"));
        assertEquals("node_auth", queryEdge.get("source"));
        assertEquals("node_db", queryEdge.get("target"));
        assertEquals("edgeStyle=orthogonalEdgeStyle;", queryEdge.get("style"));

        @SuppressWarnings("unchecked")
        Map<String, Object> edgeGeo = (Map<String, Object>) queryEdge.get("geometry");
        assertNotNull(edgeGeo);
        assertEquals("1", edgeGeo.get("relative"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sp = (Map<String, Object>) edgeGeo.get("sourcePoint");
        assertNotNull(sp);
        assertEquals(270L, ((Number) sp.get("x")).longValue());
        assertEquals(130L, ((Number) sp.get("y")).longValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> tp = (Map<String, Object>) edgeGeo.get("targetPoint");
        assertNotNull(tp);
        assertEquals(320L, ((Number) tp.get("x")).longValue());
        assertEquals(290L, ((Number) tp.get("y")).longValue());
    }

    // =========================================================================
    // Check 4: Oversized FunctionResponse remains a FunctionResponse and keeps
    // name/id, truncation marker, fingerprint, and artifact reference
    // =========================================================================

    @Test
    @DisplayName("Check 4: Oversized FunctionResponse remains a FunctionResponse and keeps name/id, truncation marker, fingerprint, and artifact reference")
    void check4_oversizedFunctionResponseRemainsFunctionResponseAndKeepsRequiredFields() {
        Map<String, Object> largePayload = new LinkedHashMap<>();
        largePayload.put("artifact_id", "artifact-uuid-9999");
        largePayload.put("large_output", "X".repeat(5000));

        FunctionResponse originalFr = FunctionResponse.builder()
                .name("generate_drawio_diagram")
                .id("call-id-test-456")
                .response(largePayload)
                .build();

        Content originalContent = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(originalFr).build()))
                .build();

        int maxChars = 300;
        Content trimmedContent = ContextCompressionPlugin.trimToolResult(originalContent, maxChars);

        assertNotNull(trimmedContent, "Trimmed content must not be null");
        List<Part> parts = trimmedContent.parts().orElse(List.of());
        assertEquals(1, parts.size(), "Trimmed content must contain exactly one part");

        Part trimmedPart = parts.get(0);
        assertTrue(trimmedPart.functionResponse().isPresent(),
                "Oversized FunctionResponse must remain a FunctionResponse object in protocol");

        FunctionResponse boundedFr = trimmedPart.functionResponse().get();

        // 1. Keeps tool name
        assertEquals("generate_drawio_diagram", boundedFr.name().orElse(""),
                "Must preserve tool name");

        // 2. Keeps call id
        assertEquals("call-id-test-456", boundedFr.id().orElse(""),
                "Must preserve call id");

        Map<String, Object> boundedMap = boundedFr.response().orElse(Map.of());

        // 3. Keeps truncation marker
        assertEquals(Boolean.TRUE, boundedMap.get("truncated"),
                "Response payload must contain truncated = true marker");

        // 4. Keeps content fingerprint
        assertNotNull(boundedMap.get("content_fingerprint"),
                "Response payload must contain content_fingerprint");
        assertFalse(String.valueOf(boundedMap.get("content_fingerprint")).isBlank(),
                "Fingerprint must not be blank");

        // 5. Keeps artifact reference
        assertEquals("artifact-uuid-9999", boundedMap.get("artifact_id"),
                "Must preserve artifact_id reference");

        // 6. Contains compact summary with trimmed indicator
        assertTrue(boundedMap.containsKey("summary"), "Must contain summary field");
        assertTrue(String.valueOf(boundedMap.get("summary")).contains("…[trimmed]"),
                "Summary must indicate it was trimmed");

        // Verify that a non-oversized FunctionResponse is NOT modified
        Map<String, Object> smallPayload = Map.of("artifact_id", "small-art", "status", "ok");
        FunctionResponse smallFr = FunctionResponse.builder()
                .name("small_tool")
                .id("call-small")
                .response(smallPayload)
                .build();
        Content smallContent = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(smallFr).build()))
                .build();
        Content untrimmedContent = ContextCompressionPlugin.trimToolResult(smallContent, 1000);
        FunctionResponse unmodifiedFr = untrimmedContent.parts().get().get(0).functionResponse().get();
        assertEquals(smallPayload, unmodifiedFr.response().orElse(Map.of()),
                "Non-oversized FunctionResponse must remain intact");
    }

    // =========================================================================
    // Check 5: Tool boundary repair never expands backward, drops orphan responses,
    // and handles chained orphans while preserving complete pairs
    // =========================================================================

    @Test
    @DisplayName("Check 5: Tool boundary repair never expands backward, drops orphan responses, and handles chained orphans while preserving complete pairs")
    void check5_toolBoundaryRepairNeverExpandsBackwardAndHandlesOrphans() {
        // Conversation turn sequence:
        // Index 0: User turn
        Content c0 = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText("Generate an order processing workflow")))
                .build();

        // Index 1: Model turn with FunctionCall
        FunctionCall fc1 = FunctionCall.builder()
                .name("render_workflow")
                .id("call_order_1001")
                .build();
        Content c1 = Content.builder()
                .role("model")
                .parts(List.of(Part.builder().functionCall(fc1).build()))
                .build();

        // Index 2: Tool turn with FunctionResponse matching call_order_1001
        FunctionResponse fr2 = FunctionResponse.builder()
                .name("render_workflow")
                .id("call_order_1001")
                .response(Map.of("status", "rendered"))
                .build();
        Content c2 = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(fr2).build()))
                .build();

        // Index 3: Model turn summarizing the result
        Content c3 = Content.builder()
                .role("model")
                .parts(List.of(Part.fromText("The order processing workflow has been created.")))
                .build();

        List<Content> contents = List.of(c0, c1, c2, c3);

        // 1. Never expands backward and drops orphan response:
        // Initial cut at index 2 would keep [c2, c3], which retains FunctionResponse c2
        // but drops matching FunctionCall c1 at index 1.
        // Under bounded tool protocol repair, adjustCutForToolBoundaries must NEVER move backward to 1;
        // it must advance past the orphan response c2 to index 3.
        int initialCut = 2;
        int adjustedCut = ContextCompressionPlugin.adjustCutForToolBoundaries(contents, initialCut);
        assertEquals(3, adjustedCut,
                "Cut must advance forward to index 3 to drop orphan response and never move backward");

        List<Content> retainedWindow = contents.subList(adjustedCut, contents.size());
        assertEquals(1, retainedWindow.size(), "Retained window must contain 1 content: c3");
        assertEquals("The order processing workflow has been created.",
                retainedWindow.get(0).parts().get().get(0).text().orElse(""));

        // 2. An already complete retained pair keeps its cut:
        int noOpAdjustedCut = ContextCompressionPlugin.adjustCutForToolBoundaries(contents, 1);
        assertEquals(1, noOpAdjustedCut,
                "When initialCut already includes the FunctionCall, complete pair must keep cut at index 1");

        // 3. Name-based matching fallback when tool ID is absent:
        FunctionCall fcByName = FunctionCall.builder()
                .name("nameless_tool")
                .build();
        Content cCallByName = Content.builder()
                .role("model")
                .parts(List.of(Part.builder().functionCall(fcByName).build()))
                .build();

        FunctionResponse frByName = FunctionResponse.builder()
                .name("nameless_tool")
                .response(Map.of("status", "ok"))
                .build();
        Content cRespByName = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(frByName).build()))
                .build();

        List<Content> nameOnlyContents = List.of(c0, cCallByName, cRespByName, c3);
        int nameAdjustedCut = ContextCompressionPlugin.adjustCutForToolBoundaries(nameOnlyContents, 1);
        assertEquals(1, nameAdjustedCut,
                "Complete pair matched by function name fallback when ID is absent must keep cut at index 1");

        // 4. Response with an ID does NOT match by name fallback when ID does not match:
        FunctionResponse frWithDifferentId = FunctionResponse.builder()
                .id("different_id")
                .name("nameless_tool")
                .response(Map.of("status", "ok"))
                .build();
        Content cRespDiffId = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(frWithDifferentId).build()))
                .build();
        List<Content> mismatchedIdContents = List.of(c0, cCallByName, cRespDiffId, c3);
        // cCallByName has name 'nameless_tool' but no ID.
        // cRespDiffId has ID 'different_id', so it must only match a call with ID 'different_id'.
        // It is an orphan and must be dropped by advancing cut to index 3.
        int mismatchedCut = ContextCompressionPlugin.adjustCutForToolBoundaries(mismatchedIdContents, 1);
        assertEquals(3, mismatchedCut,
                "Response with an ID must not match nameless call by name fallback and must be dropped");

        // 5. Chained orphan created by advancing the cut:
        // Contents sequence:
        // [0] c0: user prompt
        // [1] callA: FunctionCall id="call_A"
        // [2] callB: FunctionCall id="call_B"
        // [3] respA: FunctionResponse id="call_A"
        // [4] respB: FunctionResponse id="call_B"
        // [5] c3: summary
        FunctionCall fcA = FunctionCall.builder().id("call_A").name("toolA").build();
        Content callA = Content.builder().role("model").parts(List.of(Part.builder().functionCall(fcA).build())).build();
        FunctionCall fcB = FunctionCall.builder().id("call_B").name("toolB").build();
        Content callB = Content.builder().role("model").parts(List.of(Part.builder().functionCall(fcB).build())).build();
        FunctionResponse frA = FunctionResponse.builder().id("call_A").name("toolA").response(Map.of("res", "A")).build();
        Content respA = Content.builder().role("tool").parts(List.of(Part.builder().functionResponse(frA).build())).build();
        FunctionResponse frB = FunctionResponse.builder().id("call_B").name("toolB").response(Map.of("res", "B")).build();
        Content respB = Content.builder().role("tool").parts(List.of(Part.builder().functionResponse(frB).build())).build();

        List<Content> chainedContents = List.of(c0, callA, callB, respA, respB, c3);
        // initialCut = 2 -> window begins with callB at index 2.
        // respA (index 3) is an orphan because callA is at index 1 (not retained).
        // Advancing cut past respA moves cut to index 4.
        // At cut = 4, callB (index 2) is dropped, so respB (index 4) becomes a chained orphan!
        // Re-evaluation detects respB as an orphan and advances cut past it to index 5.
        int chainedCut = ContextCompressionPlugin.adjustCutForToolBoundaries(chainedContents, 2);
        assertEquals(5, chainedCut,
                "Re-evaluation after advancing cut must handle chained orphan and advance to index 5");

        // 6. Response preceding a later same-ID call is orphaned and removed; future call cannot validate it:
        FunctionResponse frEarlier = FunctionResponse.builder()
                .id("call_future_999")
                .name("search_tool")
                .response(Map.of("data", "stale"))
                .build();
        Content cEarlierResp = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(frEarlier).build()))
                .build();

        FunctionCall fcLater = FunctionCall.builder()
                .id("call_future_999")
                .name("search_tool")
                .build();
        Content cLaterCall = Content.builder()
                .role("model")
                .parts(List.of(Part.builder().functionCall(fcLater).build()))
                .build();

        FunctionResponse frLater = FunctionResponse.builder()
                .id("call_future_999")
                .name("search_tool")
                .response(Map.of("data", "fresh"))
                .build();
        Content cLaterResp = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(frLater).build()))
                .build();

        List<Content> invertedOrderContents = List.of(cEarlierResp, cLaterCall, cLaterResp, c3);
        int invertedCut = ContextCompressionPlugin.adjustCutForToolBoundaries(invertedOrderContents, 0);
        assertEquals(1, invertedCut,
                "Response preceding a later same-ID call must be orphaned and removed; future call cannot validate it");

        List<Content> responseBeforeCallOnly = List.of(cEarlierResp, cLaterCall, c3);
        int invertedCutNoLaterResp = ContextCompressionPlugin.adjustCutForToolBoundaries(responseBeforeCallOnly, 0);
        assertEquals(1, invertedCutNoLaterResp,
                "Response before a later call is orphaned and removed when future call has no response");

        // 7. Safe clamping and null/empty handling:
        assertEquals(0, ContextCompressionPlugin.adjustCutForToolBoundaries(null, 2));
        assertEquals(0, ContextCompressionPlugin.adjustCutForToolBoundaries(List.of(), 2));
        assertEquals(0, ContextCompressionPlugin.adjustCutForToolBoundaries(contents, -5));
        assertEquals(contents.size(), ContextCompressionPlugin.adjustCutForToolBoundaries(contents, 100));
    }

    // =========================================================================
    // Check 6: Raw context >= 95% enters aggressive compression and is accepted when compressed below 95%
    // =========================================================================

    @Test
    @DisplayName("Check 6: Raw context >= 95% enters aggressive compression and is accepted when compressed below 95%")
    void check6_rawContextAtOrAbove95PercentEntersAggressiveCompressionAndIsAccepted() {
        LightweightMonitorService monitor = Mockito.mock(LightweightMonitorService.class);
        IContextSnapshotRepository snapshots = Mockito.mock(IContextSnapshotRepository.class);

        // Fallback context window is 128_000.
        // Raw request token count: 125_000 (125,000 / 128,000 = 97.65% >= 95%).
        // Compacted request token count: 50_000 (50,000 / 128,000 = 39.06% < 95%).
        ContextTokenEstimator estimator = req -> {
            boolean isCompressed = req.contents() != null && !req.contents().isEmpty() &&
                    req.contents().get(0).parts().orElse(List.of()).stream()
                            .anyMatch(p -> p.text().orElse("").contains("[CONTEXT_COMPRESSION_ENVELOPE]"));
            return isCompressed ? 50_000L : 125_000L;
        };

        ContextCompressionPlugin plugin = new ContextCompressionPlugin(monitor, estimator, null);
        plugin.setSnapshots(snapshots);

        List<Content> rawContents = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            rawContents.add(Content.fromParts(Part.fromText("conversation message " + i)));
        }
        LlmRequest.Builder builder = LlmRequest.builder()
                .model("test-model")
                .contents(rawContents);

        // Must not throw pre-compression rejection, must complete aggressive compression and accept
        assertDoesNotThrow(() -> plugin.beforeModelCallback(null, builder));

        LlmRequest compactedRequest = builder.build();
        assertNotNull(compactedRequest.contents());
        // In aggressive compression, keep = 6, plus 1 compression envelope = 7 contents
        assertEquals(7, compactedRequest.contents().size(),
                "Aggressive compression must retain 6 recent contents plus 1 envelope");
        assertTrue(compactedRequest.contents().get(0).parts().get().get(0).text().get()
                        .contains("[CONTEXT_COMPRESSION_ENVELOPE]"),
                "First content must be the compression envelope");

        // Verify monitoring and snapshot recording succeeded
        Mockito.verify(monitor, Mockito.times(1)).compression(
                Mockito.anyString(),
                Mockito.eq(125_000),
                Mockito.eq(50_000),
                Mockito.eq("AGGRESSIVE_WINDOW+STRUCTURED_STATE+TOOL_RESULT_TRIM"),
                Mockito.anyLong()
        );
        Mockito.verify(snapshots, Mockito.times(1)).saveForInvocation(
                Mockito.anyString(),
                Mockito.contains("[CONTEXT_COMPRESSION_ENVELOPE]"),
                Mockito.anyMap(),
                Mockito.eq(125_000),
                Mockito.eq(50_000),
                Mockito.eq("AGGRESSIVE_WINDOW+STRUCTURED_STATE+TOOL_RESULT_TRIM"),
                Mockito.eq("test-model"),
                Mockito.anyLong()
        );
    }

    // =========================================================================
    // Check 7: Compressed request still >= 95% is rejected without persisting or monitoring
    // =========================================================================

    @Test
    @DisplayName("Check 7: Compressed request still >= 95% is rejected without persisting or monitoring")
    void check7_compressedRequestStillAtOrAbove95PercentIsRejected() {
        LightweightMonitorService monitor = Mockito.mock(LightweightMonitorService.class);
        IContextSnapshotRepository snapshots = Mockito.mock(IContextSnapshotRepository.class);

        // Fallback context window is 128_000.
        // Estimator always returns 125_000 (97.65% >= 95%) even after compression.
        ContextTokenEstimator estimator = req -> 125_000L;

        ContextCompressionPlugin plugin = new ContextCompressionPlugin(monitor, estimator, null);
        plugin.setSnapshots(snapshots);

        List<Content> rawContents = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            rawContents.add(Content.fromParts(Part.fromText("conversation message " + i)));
        }
        LlmRequest.Builder builder = LlmRequest.builder()
                .model("test-model")
                .contents(rawContents);

        // Must throw IllegalStateException on post-compression overflow
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> plugin.beforeModelCallback(null, builder));
        assertTrue(ex.getMessage().contains("95%"),
                "Exception message must mention 95% context limit");

        // Must NOT persist or monitor successful compression when rejected
        Mockito.verifyNoInteractions(monitor);
        Mockito.verify(snapshots, Mockito.never()).saveForInvocation(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyMap(),
                Mockito.anyInt(),
                Mockito.anyInt(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyLong()
        );
    }
}
