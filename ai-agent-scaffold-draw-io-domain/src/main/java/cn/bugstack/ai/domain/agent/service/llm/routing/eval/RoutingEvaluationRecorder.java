package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

/**
 * Recorder interface for routing evaluation records.
 */
public interface RoutingEvaluationRecorder {

    /**
     * Records an evaluation telemetry entry.
     *
     * @param record evaluation record to persist or log
     */
    void record(RoutingEvaluationRecord record);
}
