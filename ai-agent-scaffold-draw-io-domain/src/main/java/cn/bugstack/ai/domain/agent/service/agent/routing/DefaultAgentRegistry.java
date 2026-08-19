package cn.bugstack.ai.domain.agent.service.agent.routing;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory default implementation of {@link AgentRegistry}.
 *
 * <p>Initializes standard built-in agent profiles (Drawing, Coding, Document, Data, Chat)
 * while supporting dynamic agent capability extension.</p>
 */
@Slf4j
@Component
public class DefaultAgentRegistry implements AgentRegistry {

    private final Map<String, AgentProfile> agentMap = new ConcurrentHashMap<>();

    public DefaultAgentRegistry() {
        initDefaultAgents();
    }

    private void initDefaultAgents() {
        // Built-in 1: Draw.io Diagramming Agent
        registerAgent(new AgentProfile(
                "drawio-agent",
                "Draw.io Diagramming Agent",
                "Specialized in visual diagrams, architecture diagrams, sequence charts, and flowcharts generation and editing",
                Set.of(AgentCapability.DRAWING, AgentCapability.TOOL_ORCHESTRATION),
                Set.of(TaskType.DRAWIO_GENERATION, TaskType.DRAWIO_REVIEW, TaskType.SIMPLE_EDIT),
                Set.of("drawio_renderer", "xml_validator"),
                true
        ));

        // Built-in 2: Coding & Architecture Agent
        registerAgent(new AgentProfile(
                "coding-agent",
                "Coding & Engineering Agent",
                "Specialized in code generation, refactoring, API design, unit test synthesis, and software diagnostics",
                Set.of(AgentCapability.CODE_GENERATION, AgentCapability.PLANNING),
                Set.of(TaskType.CODE_GENERATION, TaskType.DIAGNOSE, TaskType.STRUCTURED_GENERATION),
                Set.of("code_linter", "git_executor"),
                true
        ));

        // Built-in 3: Document Analysis & Review Agent
        registerAgent(new AgentProfile(
                "document-agent",
                "Document Analysis & Review Agent",
                "Specialized in reading long technical papers, summarizing requirements, formatting markdown, and document extraction",
                Set.of(AgentCapability.DOCUMENT_ANALYSIS, AgentCapability.RAG),
                Set.of(TaskType.ANALYZE, TaskType.FORMAT, TaskType.SIMPLE_EDIT),
                Set.of("pdf_parser", "markdown_formatter"),
                true
        ));

        // Built-in 4: Data & Metrics Analyst Agent
        registerAgent(new AgentProfile(
                "data-analyst-agent",
                "Data & Metrics Analyst Agent",
                "Specialized in telemetry analysis, metric calculations, structured reports, and SQL querying",
                Set.of(AgentCapability.DATA_ANALYSIS, AgentCapability.AUTOMATION),
                Set.of(TaskType.ANALYZE, TaskType.STRUCTURED_GENERATION),
                Set.of("sql_executor", "metrics_calc"),
                true
        ));

        // Built-in 5: General Chat & Routing Assistant
        registerAgent(new AgentProfile(
                "general-chat-agent",
                "General Conversational Agent",
                "General helpful assistant for chit-chat, simple question answering, and fallback interaction",
                Set.of(AgentCapability.GENERAL_CHAT),
                Set.of(TaskType.GENERAL_CHAT, TaskType.SIMPLE_EDIT),
                Set.of(),
                true
        ));

        log.info("Initialized DefaultAgentRegistry with {} built-in universal agents", agentMap.size());
    }

    @Override
    public List<AgentProfile> getAgents() {
        return new ArrayList<>(agentMap.values());
    }

    @Override
    public Optional<AgentProfile> find(String agentId) {
        if (StringUtils.isBlank(agentId)) return Optional.empty();
        return Optional.ofNullable(agentMap.get(agentId.trim().toLowerCase()));
    }

    @Override
    public List<AgentProfile> getEnabledAgents() {
        return agentMap.values().stream()
                .filter(AgentProfile::enabled)
                .toList();
    }

    @Override
    public void registerAgent(AgentProfile profile) {
        if (profile != null && StringUtils.isNotBlank(profile.agentId())) {
            agentMap.put(profile.agentId().trim().toLowerCase(), profile);
            log.info("Registered Agent Profile: [id={}, name={}, capabilities={}]",
                    profile.agentId(), profile.name(), profile.capabilities());
        }
    }
}
