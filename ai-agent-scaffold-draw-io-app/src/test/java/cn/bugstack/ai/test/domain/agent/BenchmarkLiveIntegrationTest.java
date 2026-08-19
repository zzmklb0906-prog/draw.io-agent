package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Manual integration test for executing live model benchmark requests against actual LLM Providers.
 *
 * <p><strong>Safety Notice:</strong>
 * This test is strictly annotated with {@link Disabled} to ensure normal automated test runs
 * (e.g. `mvn test`) never make paid external network API requests.</p>
 */
@Disabled("Manual benchmark only - invokes paid external LLM APIs")
@SpringBootTest
class BenchmarkLiveIntegrationTest {

    @Autowired
    private BenchmarkRunner benchmarkRunner;

    @Test
    void runLiveBenchmark_executesManualEvaluation() {
        BenchmarkCase drawCase = new BenchmarkCase(
                "live-draw-1",
                TaskType.DRAWIO_GENERATION,
                "agent_drawer",
                "请生成一个简单的用户登录时序图，包含 User, Controller, Service 三个参与者。",
                new BenchmarkExpectedOutput(null, List.of(), List.of(), List.of("User", "Controller", "Service"), List.of(), 3, 2, null, null),
                GroundTruthLevel.RULE_BASED,
                Set.of("live", "drawio")
        );

        BenchmarkDataset dataset = new BenchmarkDataset("live-test-dataset", "v1", List.of(drawCase));
        BenchmarkReport report = benchmarkRunner.run(dataset, List.of("qwen3.7-flash", "qwen3.7-plus"));

        assertNotNull(report);
        System.out.println("Live Benchmark Report: " + report);
    }
}
