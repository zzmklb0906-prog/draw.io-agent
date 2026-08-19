package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * Utility loader for deserializing versioned {@link BenchmarkDataset} files from classpath or input streams.
 */
@Slf4j
public final class BenchmarkDatasetLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private BenchmarkDatasetLoader() {}

    /**
     * Loads a {@link BenchmarkDataset} from the specified classpath resource path.
     *
     * @param classpathLocation e.g. "benchmark/routing-quality-core-v1.json"
     * @return deserialized {@link BenchmarkDataset}
     * @throws IllegalArgumentException if resource cannot be found or parsed
     */
    public static BenchmarkDataset loadFromClasspath(String classpathLocation) {
        if (classpathLocation == null || classpathLocation.isBlank()) {
            throw new IllegalArgumentException("Classpath location must not be null or blank");
        }
        String cleanPath = classpathLocation.startsWith("/") ? classpathLocation.substring(1) : classpathLocation;
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(cleanPath)) {
            if (is == null) {
                throw new IllegalArgumentException("Benchmark dataset file not found on classpath: " + classpathLocation);
            }
            return OBJECT_MAPPER.readValue(is, BenchmarkDataset.class);
        } catch (Exception e) {
            log.error("Failed to load benchmark dataset from classpath [{}]", classpathLocation, e);
            throw new IllegalArgumentException("Failed to load benchmark dataset from classpath: " + classpathLocation, e);
        }
    }
}
