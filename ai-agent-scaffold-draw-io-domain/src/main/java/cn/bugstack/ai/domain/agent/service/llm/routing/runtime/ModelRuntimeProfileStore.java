package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Storage and query interface for dynamic {@link ModelRuntimeProfile}s.
 */
public interface ModelRuntimeProfileStore {

    Optional<ModelRuntimeProfile> find(String modelId);

    List<ModelRuntimeProfile> getAll();

    void save(ModelRuntimeProfile profile);
}
