package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Manual integration test for executing live model benchmark requests against actual LLM Providers.
 *
 * <p><strong>Dual safety protection:</strong></p>
 * <ol>
 *   <li>This test is annotated with {@link Disabled}, so ordinary {@code mvn test} runs never
 *       invoke paid external network APIs.</li>
 *   <li>When manually re-enabling this test, you <em>must</em> set
 *       {@link BenchmarkExecutionProperties#setEnabled(boolean) properties.setEnabled(true)};
 *       the runner's internal enabled-gate would otherwise block execution even with
 *       {@code @Disabled} removed.</li>
 * </ol>
 */
@Disabled("Manual benchmark only - invokes paid external LLM APIs")
@SpringBootTest
class BenchmarkLiveIntegrationTest {

    @Autowired
    private BenchmarkRunner benchmarkRunner;

    @Autowired
    private BenchmarkExecutionProperties benchmarkExecutionProperties;

    @Test
    void runLiveBenchmark_executesManualEvaluation() {
        // IMPORTANT: explicitly enable the runner gate for this live test.
        // Do NOT rely on the default enabled=false being overridden elsewhere.
        benchmarkExecutionProperties.setEnabled(true);

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
        assertEquals(BenchmarkRunStatus.COMPLETED, report.runStatus(),
                "Live benchmark should complete when enabled=true");
        System.out.println("Live Benchmark Report: " + report);
    }
}
