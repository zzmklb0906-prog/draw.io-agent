package cn.bugstack.ai.domain.agent.service.llm.routing.decision;

/**
 * Operational mode for model routing takeover.
 */
public enum RoutingMode {
    /**
     * Pure legacy mode. Dynamic ranking pipeline is completely bypassed.
     */
    LEGACY,

    /**
     * Shadow mode. Dynamic ranking runs in shadow for observation without affecting invocation.
     */
    SHADOW,

    /**
     * Canary mode. A controlled deterministic percentage of traffic is routed via dynamic top1.
     */
    CANARY,

    /**
     * Full dynamic mode. Dynamic ranking decision is active subject to policy validation.
     */
    DYNAMIC
}
