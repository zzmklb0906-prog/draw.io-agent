package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.model.valobj.CapabilityDescriptor;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import com.google.adk.skills.Frontmatter;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityRegistryServiceTest {

    @Test
    void shouldSearchLoadAndExecuteOnlySnapshotCapabilities() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("code"));
        registry.registerTool("code", "JAVA_TOOL", "READ_ONLY", new BaseTool("search_code", "Search Java source code and symbols") {
            @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) {
                return Single.just(Map.of("query", args.get("query"), "matches", 3));
            }
        });
        registry.registerTool("ops", "JAVA_TOOL", "READ_ONLY", new BaseTool("restart_server", "Restart a server") {
            @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) { return Single.just(Map.of()); }
        });

        var result = registry.search("inv-1", "admin", "general", "search Java code", List.of("JAVA_TOOL"), 8);
        assertEquals(1, result.capabilities().size());
        String capabilityId = result.capabilities().get(0).capability().capabilityId();
        assertEquals("search_code", registry.load(result.snapshotId(), capabilityId, null).name());
        assertEquals(3, registry.execute(result.snapshotId(), capabilityId, Map.of("query", "Checkpoint"), null).blockingGet().get("matches"));
        assertThrows(SecurityException.class, () -> registry.load(result.snapshotId(), "java_tool:ops:restart_server", null));
    }

    @Test
    void shouldLimitSearchResultsAndRestoreSnapshot() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("*"));
        for (int i=0;i<30;i++) {
            int index=i;
            registry.registerTool("group", "JAVA_TOOL", "READ_ONLY", new BaseTool("reader_"+i, "Read project file number "+i) {
                @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) { return Single.just(Map.of("index",index)); }
            });
        }
        var result=registry.search("inv","admin","general","read project file",List.of(),100);
        assertEquals(16,result.capabilities().size());
        var ids=result.capabilities().stream().map(item->item.capability().capabilityId()).toList();
        registry.restoreSnapshot("restored","inv-2","admin","general",ids);
        assertEquals(ids.size(),registry.snapshotCapabilities("restored").size());
    }

    @Test
    void shouldRankExactCapabilityIdAndToolNameFirstAmongSimilarlyNamedDistractors() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("*"));
        registerNoisyCatalog(registry);

        // 1. Exact Tool Name search
        var nameSearchResult = registry.search("inv-exact-name", "user-1", "general", "svg_export", List.of("JAVA_TOOL"), 8);
        assertFalse(nameSearchResult.capabilities().isEmpty());
        assertEquals("java_tool:export:svg_export", nameSearchResult.capabilities().get(0).capability().capabilityId());
        assertEquals("svg_export", nameSearchResult.capabilities().get(0).capability().name());

        // 2. Exact Capability ID search
        var idSearchResult = registry.search("inv-exact-id", "user-1", "general", "java_tool:export:svg_export", List.of("JAVA_TOOL"), 8);
        assertFalse(idSearchResult.capabilities().isEmpty());
        assertEquals("java_tool:export:svg_export", idSearchResult.capabilities().get(0).capability().capabilityId());

        // Verify score is higher than similarly named distractors (svg_exporter, render_svg, svg_optimizer)
        assertTrue(nameSearchResult.capabilities().get(0).score() > nameSearchResult.capabilities().get(1).score());
    }

    @Test
    void shouldSelectIntendedDrawIoSkillUsingChineseAliasAndExamplesAmongDistractors() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("*"));
        registerNoisyCatalog(registry);

        // Chinese alias search
        var aliasResult = registry.search("inv-zh-alias", "user-1", "general", "绘制架构图", List.of("SKILL"), 8);
        assertFalse(aliasResult.capabilities().isEmpty());
        assertEquals("skill:diagram:drawio-architect", aliasResult.capabilities().get(0).capability().capabilityId());

        // Chinese natural language example search
        var exampleResult = registry.search("inv-zh-example", "user-1", "general", "帮我画一个系统架构图", List.of("SKILL"), 8);
        assertFalse(exampleResult.capabilities().isEmpty());
        assertEquals("skill:diagram:drawio-architect", exampleResult.capabilities().get(0).capability().capabilityId());

        // Chinese alias "画流程图"
        var flowchartResult = registry.search("inv-zh-flowchart", "user-1", "general", "画流程图", List.of("SKILL"), 8);
        assertFalse(flowchartResult.capabilities().isEmpty());
        assertEquals("skill:diagram:drawio-architect", flowchartResult.capabilities().get(0).capability().capabilityId());
    }

    @Test
    void shouldSuppressNegativeExamplesInFavorOfSuitableCapability() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("*"));
        registerNoisyCatalog(registry);

        // Query "生成PPT": ppt-presentation-builder should rank first, drawio-architect (negative example "生成PPT") suppressed
        var pptResult = registry.search("inv-ppt", "user-1", "general", "生成PPT", List.of("SKILL"), 8);
        assertFalse(pptResult.capabilities().isEmpty());
        assertEquals("skill:office:ppt-presentation-builder", pptResult.capabilities().get(0).capability().capabilityId());

        // Query "batch email workflow": batch_email_sender should rank first, batch-workflow-automator suppressed
        var emailResult = registry.search("inv-email", "user-1", "general", "batch email workflow", List.of(), 8);
        assertFalse(emailResult.capabilities().isEmpty());
        assertEquals("java_tool:notify:batch_email_sender", emailResult.capabilities().get(0).capability().capabilityId());
    }

    @Test
    void shouldFilterByRequestedTypesExcludingDistractors() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("*"));
        registerNoisyCatalog(registry);

        // Filter by SKILL only
        var skillOnly = registry.search("inv-skills", "user-1", "general", "diagram", List.of("SKILL"), 10);
        assertFalse(skillOnly.capabilities().isEmpty());
        assertTrue(skillOnly.capabilities().stream().allMatch(c -> "SKILL".equalsIgnoreCase(c.capability().type())));

        // Filter by JAVA_TOOL only
        var toolOnly = registry.search("inv-tools", "user-1", "general", "diagram", List.of("JAVA_TOOL"), 10);
        assertFalse(toolOnly.capabilities().isEmpty());
        assertTrue(toolOnly.capabilities().stream().allMatch(c -> "JAVA_TOOL".equalsIgnoreCase(c.capability().type())));
    }

    @Test
    void shouldApplyAgentGroupAndRiskPolicyFilteringBeforeRanking() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registerNoisyCatalog(registry);

        // Agent restricted to "render" group and READ_ONLY policy
        registry.allowAgentPolicy("restricted-agent", List.of("render"), "READ_ONLY");

        var result = registry.search("inv-restricted", "user-1", "restricted-agent", "render", List.of(), 16);
        assertFalse(result.capabilities().isEmpty());
        for (var scored : result.capabilities()) {
            assertEquals("render", scored.capability().group());
            assertEquals("READ_ONLY", scored.capability().riskLevel());
        }
        // Verify group "export" is not present
        assertTrue(result.capabilities().stream().noneMatch(c -> "export".equals(c.capability().group())));
        // Verify higher-risk tool in the same "render" group is excluded for READ_ONLY agent
        assertTrue(result.capabilities().stream().noneMatch(c -> "java_tool:render:render_remote_cluster".equals(c.capability().capabilityId())));

        // Agent with APPROVAL_REQUIRED policy can see the higher-risk capability in the "render" group
        registry.allowAgentPolicy("approval-agent", List.of("render"), "APPROVAL_REQUIRED");
        var approvalResult = registry.search("inv-approval", "user-1", "approval-agent", "render_remote_cluster", List.of(), 16);
        assertFalse(approvalResult.capabilities().isEmpty());
        assertTrue(approvalResult.capabilities().stream().anyMatch(c -> "java_tool:render:render_remote_cluster".equals(c.capability().capabilityId())));
    }

    @Test
    void shouldCapTopKAt16AndMaintainDeterministicTies() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("*"));
        registerNoisyCatalog(registry);

        var result1 = registry.search("inv-tie-1", "user-1", "general", "diagram export", List.of(), 100);
        var result2 = registry.search("inv-tie-2", "user-1", "general", "diagram export", List.of(), 100);

        assertTrue(result1.capabilities().size() <= 16);
        assertEquals(result1.capabilities().size(), result2.capabilities().size());

        for (int i = 0; i < result1.capabilities().size(); i++) {
            assertEquals(result1.capabilities().get(i).capability().capabilityId(),
                         result2.capabilities().get(i).capability().capabilityId());
            assertEquals(result1.capabilities().get(i).score(),
                         result2.capabilities().get(i).score());
        }
    }

    @Test
    void shouldLoadAndExecuteSelectedFakeSkillSnapshotAndRejectNonSnapshotDistractor() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("*"));
        registerNoisyCatalog(registry);

        var result = registry.search("inv-skill-exec", "user-1", "general", "绘制架构图", List.of("SKILL"), 5);
        String snapshotId = result.snapshotId();
        String selectedId = "skill:diagram:drawio-architect";

        // Load snapshot capability
        CapabilityDescriptor loaded = registry.load(snapshotId, selectedId, null);
        assertNotNull(loaded);
        assertEquals("drawio-architect", loaded.name());
        assertTrue(loaded.aliases().contains("绘制架构图"));
        assertTrue(loaded.examples().contains("帮我画一个系统架构图"));

        // Execute snapshot capability
        Map<String, Object> execResult = registry.execute(snapshotId, selectedId, Map.of(), null).blockingGet();
        assertEquals("skill_loaded:drawio-architect", execResult.get("status"));

        // Execute skill with resource
        Map<String, Object> resourceResult = registry.execute(snapshotId, selectedId, Map.of("resourcePath", "template.xml"), null).blockingGet();
        assertEquals("resource_loaded:template.xml", resourceResult.get("status"));

        // Non-snapshot distractor cannot be loaded or executed
        assertThrows(SecurityException.class, () -> registry.load(snapshotId, "skill:office:pdf-report-generator", null));
        assertThrows(SecurityException.class, () -> registry.execute(snapshotId, "skill:office:pdf-report-generator", Map.of(), null));
    }

    @Test
    void shouldNormalizeMalformedAndOversizedMetadataWithoutFailure() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", List.of("*"));

        String longEntry = "x".repeat(300);
        List<Object> mixedNegativeList = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            mixedNegativeList.add("item_" + i);
        }
        // Add duplicates, blank entries, nulls, and non-string types (numbers, maps, booleans)
        mixedNegativeList.add("item_0");
        mixedNegativeList.add("   ");
        mixedNegativeList.add(null);
        mixedNegativeList.add(99999);
        mixedNegativeList.add(Map.of("nested", "ignored"));
        mixedNegativeList.add(false);

        BaseTool tool = new BaseTool("robust_tool", "Tool for testing metadata normalization") {
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
                return Single.just(Map.of());
            }
        };
        // 1. Array with mixed String and non-string elements
        tool.setCustomMetadata("aliases", new Object[]{"array_alias", 12345, true, Map.of("foo", "bar")});
        // 2. Collection with long entry, valid string, and malformed non-string elements
        tool.setCustomMetadata("examples", List.of(longEntry, "valid_example", 42, Map.of("k", "v")));
        // 3. Large collection with non-string elements, blanks, duplicates
        tool.setCustomMetadata("negativeExamples", mixedNegativeList);

        registry.registerTool("test", "JAVA_TOOL", "READ_ONLY", tool);

        var searchResult = registry.search("inv-norm", "user-1", "general", "robust_tool", List.of(), 1);
        CapabilityDescriptor descriptor = searchResult.capabilities().get(0).capability();

        // 1. Array of objects: non-strings ignored, only valid string kept
        assertEquals(List.of("array_alias"), descriptor.aliases());

        // 2. Long entry truncated to 200 chars, non-string elements ignored
        assertEquals(2, descriptor.examples().size());
        assertEquals(200, descriptor.examples().get(0).length());
        assertEquals("valid_example", descriptor.examples().get(1));

        // 3. Large list capped at 16 entries, duplicates/blanks/non-strings removed
        assertEquals(16, descriptor.negativeExamples().size());
        assertEquals("item_0", descriptor.negativeExamples().get(0));
        assertEquals("item_15", descriptor.negativeExamples().get(15));
        assertTrue(descriptor.negativeExamples().stream().noneMatch(s -> s.contains("99999") || s.contains("nested")));

        // 4. Content version stability and invalidation check
        String contentVersion1 = descriptor.contentVersion();
        assertNotNull(contentVersion1);

        // Update tool metadata and re-register
        tool.setCustomMetadata("aliases", "modified_alias");
        registry.registerTool("test", "JAVA_TOOL", "READ_ONLY", tool);
        var searchResult2 = registry.search("inv-norm-2", "user-1", "general", "robust_tool", List.of(), 1);
        String contentVersion2 = searchResult2.capabilities().get(0).capability().contentVersion();

        assertNotEquals(contentVersion1, contentVersion2);
        assertEquals(descriptor.schemaVersion(), searchResult2.capabilities().get(0).capability().schemaVersion());
    }

    // --- Private Test Helpers and Distractor Catalog ---

    private void registerNoisyCatalog(CapabilityRegistryService registry) {
        BaseTool loadSkill = new BaseTool("load_skill", "Load skill content") {
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
                return Single.just(Map.of("status", "skill_loaded:" + args.get("skill_name")));
            }
        };
        BaseTool loadResource = new BaseTool("load_skill_resource", "Load skill resource file") {
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
                return Single.just(Map.of("status", "resource_loaded:" + args.get("file_path")));
            }
        };

        // 10+ Fake Skills (Instantiated in test code only)
        registry.registerSkill("diagram", createFakeSkillFrontmatter("drawio-architect",
                "Design Draw.io architecture diagrams and flowcharts",
                List.of("画图", "流程图", "绘制架构图", "drawio"),
                List.of("帮我画一个系统架构图", "用drawio生成流程图"),
                List.of("生成PPT", "思维导图")), loadSkill, loadResource);

        registry.registerSkill("office", createFakeSkillFrontmatter("ppt-presentation-builder",
                "Generate PPT presentation slides and decks with flowchart slides",
                List.of("生成PPT", "制作PPT", "演示文稿"),
                List.of("生成项目汇报PPT"),
                List.of("画流程图", "绘制架构图", "Draw.io")), loadSkill, loadResource);

        registry.registerSkill("diagram", createFakeSkillFrontmatter("mindmap-organizer",
                "Create mind map node hierarchies and brainstorm trees",
                List.of("思维导图", "脑图", "mindmap"),
                List.of("整理知识点思维导图"),
                List.of("系统架构图", "UML时序图")), loadSkill, loadResource);

        registry.registerSkill("diagram", createFakeSkillFrontmatter("plantuml-generator",
                "Generate PlantUML sequence and class diagrams",
                List.of("UML图", "时序图", "类图"),
                List.of("绘制UML时序图"),
                List.of("Draw.io", "PPT")), loadSkill, loadResource);

        registry.registerSkill("office", createFakeSkillFrontmatter("pdf-report-generator",
                "Generate formatted PDF report documents and tables",
                List.of("PDF报告", "导出PDF"),
                List.of("生成月度分析PDF"),
                List.of()), loadSkill, loadResource);

        registry.registerSkill("workflow", createFakeSkillFrontmatter("bpmn-process-designer",
                "Design BPMN 2.0 business process diagrams and tasks",
                List.of("BPMN设计", "业务流程图"),
                List.of("设计审批流BPMN"),
                List.of()), loadSkill, loadResource);

        registry.registerSkill("database", createFakeSkillFrontmatter("er-database-modeler",
                "Design entity relationship ER diagrams for relational databases",
                List.of("ER图", "数据库建模"),
                List.of("绘制电商数据库ER图"),
                List.of()), loadSkill, loadResource);

        registry.registerSkill("infra", createFakeSkillFrontmatter("network-topology-sketcher",
                "Sketch network topology and subnet routing diagrams",
                List.of("网络拓扑", "拓扑图"),
                List.of("设计VPC网络拓扑图"),
                List.of()), loadSkill, loadResource);

        registry.registerSkill("infra", createFakeSkillFrontmatter("cloud-infra-architect",
                "Design cloud infrastructure blueprints on AWS and GCP",
                List.of("云架构", "AWS架构"),
                List.of("设计微服务云架构"),
                List.of()), loadSkill, loadResource);

        registry.registerSkill("automation", createFakeSkillFrontmatter("batch-workflow-automator",
                "Automate batch job processing workflows and execution pipelines",
                List.of("工作流自动化", "批处理"),
                List.of(),
                List.of("batch email workflow", "邮件通知")), loadSkill, loadResource);

        // 22+ Fake Tools (Instantiated in test code only)
        registry.registerTool("export", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("svg_export", "Export diagram as SVG file",
                        List.of("svg_export", "export_svg", "svg"),
                        List.of("export to svg", "save as svg"),
                        List.of("png export", "raster render")));

        registry.registerTool("export", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("svg_exporter", "SVG exporter tool for raw vector data",
                        List.of("svg_exporter_tool"), List.of(), List.of()));

        registry.registerTool("render", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("render_svg", "Render SVG vector diagram to graphic context",
                        List.of("render_svg_graphic"), List.of(), List.of()));

        registry.registerTool("optimizer", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("svg_optimizer", "Optimize and compress SVG XML structure",
                        List.of("optimize_svg"), List.of(), List.of()));

        registry.registerTool("export", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("export_png", "Export canvas as PNG raster image",
                        List.of("png_export"), List.of(), List.of()));

        registry.registerTool("export", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("export_pdf", "Export diagram to PDF document format",
                        List.of("pdf_export"), List.of(), List.of()));

        registry.registerTool("export", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("export_jpeg", "Export canvas to JPEG format",
                        List.of(), List.of(), List.of()));

        registry.registerTool("export", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("export_drawio_xml", "Export Draw.io XML graph file",
                        List.of("drawio_xml"), List.of(), List.of()));

        registry.registerTool("render", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("render_canvas", "Render HTML5 canvas drawing",
                        List.of(), List.of(), List.of()));

        registry.registerTool("render", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("render_mermaid", "Render mermaid text diagram",
                        List.of("mermaid"), List.of(), List.of()));

        registry.registerTool("render", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("render_plantuml", "Render plantuml text diagram",
                        List.of("plantuml"), List.of(), List.of()));

        registry.registerTool("render", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("render_graphviz", "Render graphviz dot diagram",
                        List.of("graphviz"), List.of(), List.of()));

        registry.registerTool("render", "JAVA_TOOL", "REQUIRES_APPROVAL",
                createFakeTool("render_remote_cluster", "Render diagram on remote compute cluster",
                        List.of("render_remote"), List.of(), List.of()));

        registry.registerTool("notify", "JAVA_TOOL", "APPROVAL_REQUIRED",
                createFakeTool("batch_email_sender", "Send batch email notifications to users",
                        List.of("email_sender", "batch_email"),
                        List.of("batch email workflow", "send mass emails"),
                        List.of()));

        registry.registerTool("notify", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("email_dispatcher", "Dispatch single transactional emails",
                        List.of(), List.of(), List.of()));

        registry.registerTool("notify", "JAVA_TOOL", "APPROVAL_REQUIRED",
                createFakeTool("sms_sender", "Send SMS alert messages",
                        List.of(), List.of(), List.of()));

        registry.registerTool("notify", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("slack_webhook", "Post notification to Slack channel",
                        List.of(), List.of(), List.of()));

        registry.registerTool("data", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("db_query_tool", "Execute read-only SQL query against database",
                        List.of("sql_query"), List.of(), List.of()));

        registry.registerTool("data", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("db_schema_inspector", "Inspect database schema metadata",
                        List.of(), List.of(), List.of()));

        registry.registerTool("data", "JAVA_TOOL", "APPROVAL_REQUIRED",
                createFakeTool("db_migration_tool", "Run database schema migration script",
                        List.of(), List.of(), List.of()));

        registry.registerTool("filesystem", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("file_reader", "Read local workspace file content",
                        List.of("read_file"), List.of(), List.of()));

        registry.registerTool("filesystem", "JAVA_TOOL", "REQUIRES_APPROVAL",
                createFakeTool("file_writer", "Write file content to disk",
                        List.of("write_file"), List.of(), List.of()));

        registry.registerTool("vcs", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("git_diff_viewer", "Show git diff between commits",
                        List.of("git_diff"), List.of(), List.of()));

        registry.registerTool("vcs", "JAVA_TOOL", "REQUIRES_APPROVAL",
                createFakeTool("git_branch_creator", "Create new git branch in repository",
                        List.of(), List.of(), List.of()));

        registry.registerTool("network", "JAVA_TOOL", "READ_ONLY",
                createFakeTool("http_fetcher", "Send HTTP GET or POST request to endpoint",
                        List.of("http_client"), List.of(), List.of()));
    }

    private BaseTool createFakeTool(String name, String description, List<String> aliases, List<String> examples, List<String> negativeExamples) {
        BaseTool tool = new BaseTool(name, description) {
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
                return Single.just(Map.of("tool", name, "result", "ok"));
            }
        };
        if (aliases != null && !aliases.isEmpty()) tool.setCustomMetadata("aliases", aliases);
        if (examples != null && !examples.isEmpty()) tool.setCustomMetadata("examples", examples);
        if (negativeExamples != null && !negativeExamples.isEmpty()) tool.setCustomMetadata("negativeExamples", negativeExamples);
        return tool;
    }

    private Frontmatter createFakeSkillFrontmatter(String name, String description, List<String> aliases, List<String> examples, List<String> negativeExamples) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (aliases != null && !aliases.isEmpty()) metadata.put("aliases", aliases);
        if (examples != null && !examples.isEmpty()) metadata.put("examples", examples);
        if (negativeExamples != null && !negativeExamples.isEmpty()) metadata.put("negativeExamples", negativeExamples);
        return Frontmatter.builder()
                .name(name)
                .description(description)
                .metadata(metadata)
                .build();
    }
}
