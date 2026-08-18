package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

/**
 * High-level Task Intent Classification for Model Routing.
 *
 * <p>Represents coarse-grained functional categories used to establish baseline capability demands.</p>
 */
public enum TaskType {
    SIMPLE_EDIT,
    FORMAT,
    SUMMARIZE,
    EXTRACT,
    GENERAL_CHAT,
    ANALYZE,
    DIAGNOSE,
    PLAN,
    CODE_GENERATION,
    CODE_ANALYSIS,
    DRAWIO_ANALYSIS,
    DRAWIO_GENERATION,
    DRAWIO_REVIEW,
    STRUCTURED_GENERATION,
    TOOL_ORCHESTRATION,
    UNKNOWN
}
