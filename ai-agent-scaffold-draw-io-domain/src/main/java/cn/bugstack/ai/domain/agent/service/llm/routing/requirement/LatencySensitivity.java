package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

/**
 * Operational Latency Sensitivity for an LLM Request requirement.
 *
 * <p>Indicates how sensitive the calling context/agent is to response latency:
 * <ul>
 *   <li>{@link #HIGH}: Strict latency requirement (e.g. interactive edits, autocomplete, chat).</li>
 *   <li>{@link #NORMAL}: Balanced latency expectations (e.g. standard generation, summarization).</li>
 *   <li>{@link #LOW}: High tolerance for longer processing times (e.g. deep analysis, complex diagnostic workflows).</li>
 * </ul>
 * </p>
 */
public enum LatencySensitivity {
    LOW,
    NORMAL,
    HIGH
}
