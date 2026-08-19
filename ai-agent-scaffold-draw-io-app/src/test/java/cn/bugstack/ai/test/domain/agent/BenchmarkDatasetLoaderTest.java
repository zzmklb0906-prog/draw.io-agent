package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.BenchmarkDataset;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.BenchmarkDatasetLoader;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkDatasetLoaderTest {

    @Test
    void loadFromClasspath_validResource_loadsDatasetCorrectly() {
        BenchmarkDataset dataset = BenchmarkDatasetLoader.loadFromClasspath("benchmark/routing-quality-core-v1.json");

        assertNotNull(dataset);
        assertEquals("routing-quality-core", dataset.datasetId());
        assertEquals("v1.0.0", dataset.version());
        assertEquals(8, dataset.cases().size());

        // Verify task type coverage
        assertTrue(dataset.cases().stream().anyMatch(c -> c.taskType() == TaskType.STRUCTURED_GENERATION));
        assertTrue(dataset.cases().stream().anyMatch(c -> c.taskType() == TaskType.DRAWIO_GENERATION));
        assertTrue(dataset.cases().stream().anyMatch(c -> c.taskType() == TaskType.EXTRACT));
        assertTrue(dataset.cases().stream().anyMatch(c -> c.taskType() == TaskType.ANALYZE));
        assertTrue(dataset.cases().stream().anyMatch(c -> c.taskType() == TaskType.DIAGNOSE));
        assertTrue(dataset.cases().stream().anyMatch(c -> c.taskType() == TaskType.SIMPLE_EDIT));
    }

    @Test
    void loadFromClasspath_nonExistentResource_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                BenchmarkDatasetLoader.loadFromClasspath("benchmark/non-existent.json"));
    }
}
