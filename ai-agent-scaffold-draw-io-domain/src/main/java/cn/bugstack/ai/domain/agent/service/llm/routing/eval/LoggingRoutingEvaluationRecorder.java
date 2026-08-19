package cn.bugstack.ai.domain.agent.service.llm.routing.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Structured JSON Logging implementation of {@link RoutingEvaluationRecorder}.
 */
@Slf4j(topic = "cn.bugstack.ai.routing.eval")
@Component
public class LoggingRoutingEvaluationRecorder implements RoutingEvaluationRecorder {

    private final ObjectMapper objectMapper;

    public LoggingRoutingEvaluationRecorder() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public LoggingRoutingEvaluationRecorder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }


    @Override
    public void record(RoutingEvaluationRecord record) {
        if (record == null) return;
        try {
            String json = objectMapper.writeValueAsString(record);
            log.info("[routing_shadow_eval] {}", json);
        } catch (Exception e) {
            log.warn("Failed to serialize RoutingEvaluationRecord to JSON: {}", e.getMessage());
        }
    }
}
