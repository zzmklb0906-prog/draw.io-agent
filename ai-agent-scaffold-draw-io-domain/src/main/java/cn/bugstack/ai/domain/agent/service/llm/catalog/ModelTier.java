package cn.bugstack.ai.domain.agent.service.llm.catalog;

/**
 * Operational tier classification for LLM models in the catalog.
 *
 * <p>Used for deterministic tier-first gating and cost-optimized routing:
 * <ul>
 *   <li>{@link #FAST}: Cost-effective, high-throughput models suitable for simple edits and formatting.</li>
 *   <li>{@link #BALANCED}: Standard general-purpose models balancing capability and cost.</li>
 *   <li>{@link #REASONING}: High-capability models required for complex diagnosis, deep analysis, and planning.</li>
 * </ul>
 * </p>
 */
public enum ModelTier {
    FAST,
    BALANCED,
    REASONING
}
