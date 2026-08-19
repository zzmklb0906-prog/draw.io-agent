package cn.bugstack.ai.domain.agent.service.llm.routing.runtime;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory thread-safe implementation of {@link ModelRuntimeProfileStore}.
 */
@Component
public class InMemoryModelRuntimeProfileStore implements ModelRuntimeProfileStore {

    private final Map<String, ModelRuntimeProfile> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ModelRuntimeProfile> find(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(modelId.trim().toLowerCase()));
    }

    @Override
    public List<ModelRuntimeProfile> getAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void save(ModelRuntimeProfile profile) {
        if (profile != null && StringUtils.isNotBlank(profile.modelId())) {
            store.put(profile.modelId().trim().toLowerCase(), profile);
        }
    }
}
