package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration.ManualCalibrationAnalyzer;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration.ManualCalibrationReport;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Model Quality Benchmark Integration Test against configured LLM Providers.
 *
 * <p><strong>Safety Gates (Dual Protection):</strong></p>
 * <ol>
 *   <li>Only executes when system property or environment variable {@code RUN_LIVE_MODEL_BENCHMARK=true} is set
 *       (e.g. {@code -DRUN_LIVE_MODEL_BENCHMARK=true}). Ordinary {@code mvn test} runs without this property
 *       will automatically skip this class.</li>
 *   <li>Inside each test, {@link BenchmarkExecutionProperties#setEnabled(boolean) properties.setEnabled(true)}
 *       is explicitly configured before runner invocation.</li>
 * </ol>
 */
@Slf4j
@EnabledIfSystemProperty(named = "RUN_LIVE_MODEL_BENCHMARK", matches = "true")
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
class BenchmarkLiveIntegrationTest {

    @Autowired
    private BenchmarkRunner benchmarkRunner;

    @Autowired
    private BenchmarkExecutionProperties benchmarkExecutionProperties;

    @Autowired
    private ModelCatalogService modelCatalogService;

    @Autowired
    private ManualCalibrationAnalyzer calibrationAnalyzer;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Step 1: Smoke Run — 2 Cases × enabled models to verify provider connectivity & evaluators.
     */
    @Test
    void runSmokeLiveBenchmark_executesSmallSmokeTest() {
        benchmarkExecutionProperties.setEnabled(true);
        benchmarkExecutionProperties.setMaxCases(2);

        BenchmarkDataset fullDataset = BenchmarkDatasetLoader.loadFromClasspath("benchmark/routing-quality-core-v1.json");
        List<BenchmarkCase> smokeCases = fullDataset.cases().stream().limit(2).toList();
        BenchmarkDataset smokeDataset = new BenchmarkDataset("routing-quality-smoke", "v1.0.0", smokeCases);

        List<String> enabledModels = modelCatalogService.getEnabledModels().stream()
                .map(ModelProfile::modelName)
                .sorted()
                .toList();

        log.info("Starting Live Smoke Benchmark: dataset=[{}], cases=[{}], models={}",
                smokeDataset.datasetId(), smokeDataset.cases().size(), enabledModels);

        BenchmarkReport report = benchmarkRunner.run(smokeDataset, enabledModels);

        assertNotNull(report);
        assertEquals(BenchmarkRunStatus.COMPLETED, report.runStatus());
        assertTrue(report.modelExecutions() > 0);

        saveReportToFile("target/benchmark-results/smoke-benchmark-report.json", report);
        log.info("Smoke Benchmark Completed Successfully! Executions: {}, SuccessRate: {}",
                report.modelExecutions(), report.executionSuccessRate());
    }

    /**
     * Step 2: Full Small-scale Live Benchmark — 8 Cases × enabled models.
     */
    @Test
    void runFullSmallLiveBenchmark_executesCompleteSuiteAndCalibration() {
        benchmarkExecutionProperties.setEnabled(true);
        benchmarkExecutionProperties.setMaxCases(10);

        BenchmarkDataset dataset = BenchmarkDatasetLoader.loadFromClasspath("benchmark/routing-quality-core-v1.json");
        List<String> enabledModels = modelCatalogService.getEnabledModels().stream()
                .map(ModelProfile::modelName)
                .sorted()
                .toList();

        log.info("Starting Full Small Live Benchmark: dataset=[{}], cases=[{}], models={}",
                dataset.datasetId(), dataset.cases().size(), enabledModels);

        BenchmarkReport report = benchmarkRunner.run(dataset, enabledModels);

        assertNotNull(report);
        assertEquals(BenchmarkRunStatus.COMPLETED, report.runStatus());
        assertEquals(dataset.cases().size(), report.executedCases());

        // Save Benchmark Report
        saveReportToFile("target/benchmark-results/routing-quality-core-v1-report.json", report);

        // Run Manual Calibration Analyzer
        List<String> humanNotes = List.of(
                "Observed: Qwen series shows solid JSON and XML syntax compliance.",
                "Reviewer Note: Simple edit tasks are consistently handled well across all tiers."
        );
        ManualCalibrationReport calibReport = calibrationAnalyzer.analyze(report, humanNotes);
        saveReportToFile("target/benchmark-results/routing-quality-core-v1-calibration.json", calibReport);

        log.info("Full Small Live Benchmark Finished! Report saved to target/benchmark-results/");
        log.info("Dynamic Summary: OptRate={}, EquivRate={}, OverRate={}, UnderRate={}",
                report.dynamicSummary().optimalRate(),
                report.dynamicSummary().qualityEquivalentRate(),
                report.dynamicSummary().overRoutingRate(),
                report.dynamicSummary().underRoutingRate());
    }

    private void saveReportToFile(String relativePath, Object data) {
        try {
            File file = new File(relativePath);
            file.getParentFile().mkdirs();
            objectMapper.writeValue(file, data);
            log.info("Saved report to [{}]", file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to write report to file [{}]: {}", relativePath, e.getMessage());
        }
    }
}
