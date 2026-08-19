package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Runtime Feedback Service.
 *
 * <p>Coordinates telemetry feedback ingestion and provides read access to aggregated runtime profiles.</p>
 */
@Slf4j
@Service
public class RuntimeFeedbackService {

    private final RuntimeFeedbackCollector collector;
    private final ModelRuntimeProfileStore profileStore;

    public RuntimeFeedbackService(RuntimeFeedbackCollector collector,
                                  ModelRuntimeProfileStore profileStore) {
        this.collector = collector;
        this.profileStore = profileStore;
    }

    /**
     * Records a model execution telemetry result.
     */
    public void recordExecution(ModelExecutionResult result) {
        if (collector != null && result != null) {
            try {
                collector.collect(result);
            } catch (Exception e) {
                log.warn("Failed to record model execution feedback (non-fatal): {}", e.getMessage());
            }
        }
    }

    /**
     * Looks up the latest aggregated runtime profile for a model.
     */
    public Optional<ModelRuntimeProfile> getProfile(String modelId) {
        return profileStore != null ? profileStore.find(modelId) : Optional.empty();
    }
}
