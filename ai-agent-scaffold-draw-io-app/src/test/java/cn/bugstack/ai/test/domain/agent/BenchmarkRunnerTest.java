package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScoringProperties;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.WeightedModelScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BenchmarkRunner} — all tests use FakeBenchmarkModelInvoker (zero network calls).
 */
class BenchmarkRunnerTest {

    private BenchmarkRunner runner;
    private FakeBenchmarkModelInvoker fakeInvoker;
    private ModelCatalogService modelCatalogService;
    private BenchmarkExecutionProperties properties;

    @BeforeEach
    void setUp() {
        this.fakeInvoker = new FakeBenchmarkModelInvoker();
        List<ResponseQualityEvaluator> evaluators = List.of(
                new StructuredResponseQualityEvaluator(),
                new DrawioResponseQualityEvaluator(),
                new TextResponseQualityEvaluator()
        );

        RoutingQualityEvaluator qualityEvaluator = new RoutingQualityEvaluator();
        this.modelCatalogService = Mockito.mock(ModelCatalogService.class);
        WeightedModelScorer scorer = new WeightedModelScorer(new ModelScoringProperties());
        ModelRoutingService legacyRouter = Mockito.mock(ModelRoutingService.class);

        when(legacyRouter.route(any())).thenReturn(
                new ModelRoutingService.Decision("qwen3.7-plus", "TEST", 2, "Test", Map.of(), List.of(), List.of())
        );

        RoutingContextFactory ctxFactory = Mockito.mock(RoutingContextFactory.class);
        RoutingRequirementService reqService = Mockito.mock(RoutingRequirementService.class);
        ModelConstraintFilteringService filterService = Mockito.mock(ModelConstraintFilteringService.class);
        DynamicModelRankingService rankingService = Mockito.mock(DynamicModelRankingService.class);

        this.properties = new BenchmarkExecutionProperties();
        this.properties.setEnabled(true);   // enable for unit tests using FakeInvoker
        this.properties.setMaxCases(30);

        this.runner = new BenchmarkRunner(
                fakeInvoker,
                evaluators,
                qualityEvaluator,
                modelCatalogService,
                scorer,
                legacyRouter,
                ctxFactory,
                reqService,
                filterService,
                rankingService,
                properties
        );
    }

    // =========================================================================
    // Fix 1 — Enabled Safety Gate
    // =========================================================================

    @Test
    void benchmarkDisabled_doesNotInvokeAnyModel() {
        // Use a counting invoker that records calls
        CountingFakeInvoker countingInvoker = new CountingFakeInvoker();
        BenchmarkExecutionProperties disabledProps = new BenchmarkExecutionProperties();
        disabledProps.setEnabled(false);

        BenchmarkRunner disabledRunner = buildRunner(countingInvoker, disabledProps);

        BenchmarkDataset dataset = createDataset(3);
        BenchmarkReport report = disabledRunner.run(dataset, List.of("qwen3.7-flash", "qwen3.7-plus"));

        assertEquals(0, countingInvoker.getInvocationCount(), "No model invocations should occur when disabled");
        assertEquals(BenchmarkRunStatus.DISABLED, report.runStatus(), "Report status must be DISABLED");
        assertEquals(0, report.modelExecutions(), "modelExecutions must be 0 when disabled");
        assertEquals(0, report.executedCases(), "executedCases must be 0 when disabled");
        assertNotNull(report.skippedReason(), "skippedReason must be provided when disabled");
        assertFalse(report.skippedReason().isBlank(), "skippedReason must not be blank");
    }

    @Test
    void benchmarkDisabled_withNullDataset_stillReturnsDisabledStatus() {
        CountingFakeInvoker countingInvoker = new CountingFakeInvoker();
        BenchmarkExecutionProperties disabledProps = new BenchmarkExecutionProperties();
        disabledProps.setEnabled(false);

        BenchmarkRunner disabledRunner = buildRunner(countingInvoker, disabledProps);
        BenchmarkReport report = disabledRunner.run(null, List.of("qwen3.7-flash"));

        assertEquals(0, countingInvoker.getInvocationCount());
        assertEquals(BenchmarkRunStatus.DISABLED, report.runStatus());
    }

    @Test
    void benchmarkEnabled_noData_returnsNoDataStatus() {
        BenchmarkReport report = runner.run(new BenchmarkDataset("empty", "v1", List.of()), List.of("m1"));

        assertEquals(BenchmarkRunStatus.NO_DATA, report.runStatus());
        assertEquals(0, report.modelExecutions());
    }

    // =========================================================================
    // Fix 2 — Failed Execution Does Not Pollute Quality Averages
    // =========================================================================

    @Test
    void failedExecution_doesNotPolluteAverageQuality() {
        // 3 executions: success(score≈90), success(score≈90), timeout failure
        // averageQuality must be ~90, NOT ~60
        PartialFailInvoker invoker = new PartialFailInvoker(
                Map.of("timeout-model", 2), // timeout-model fails on 3rd invocation (index 2)
                "timeout-model"
        );
        // Use a simple always-success invoker for the two good models
        // We simulate: 2 success results with high JSON quality + 1 failure
        BenchmarkCase jsonCase = new BenchmarkCase(
                "c1", TaskType.STRUCTURED_GENERATION, "agent",
                "Generate JSON",
                new BenchmarkExpectedOutput("{}", List.of("name"), List.of(), List.of(), List.of(), null, null, null, null),
                GroundTruthLevel.DETERMINISTIC, Set.of()
        );
        BenchmarkDataset dataset = new BenchmarkDataset("ds", "v1", List.of(jsonCase));

        // Invoker: first call success, second call success, third call failure
        SequentialInvoker seqInvoker = new SequentialInvoker(List.of(
                BenchmarkRawResponse.success("{\"name\": \"A\"}", 100L, 50L, 20L, 70L),  // model-a: success, score ~90
                BenchmarkRawResponse.success("{\"name\": \"B\"}", 110L, 50L, 20L, 70L),  // model-b: success, score ~90
                BenchmarkRawResponse.failure("TimeoutException", "Request timed out", 5000L) // model-c: failure
        ));

        BenchmarkRunner testRunner = buildRunner(seqInvoker, properties);
        BenchmarkReport report = testRunner.run(dataset, List.of("model-a", "model-b", "model-c"));

        assertEquals(3, report.modelExecutions(), "All 3 executions must be counted");

        ModelOverallQuality aQual = report.perModelQuality().get("model-a");
        ModelOverallQuality bQual = report.perModelQuality().get("model-b");
        ModelOverallQuality cQual = report.perModelQuality().get("model-c");

        // model-a: 1 execution, 1 success, quality ~90
        assertNotNull(aQual);
        assertEquals(1L, aQual.successCount());
        assertNotNull(aQual.averageQuality());
        assertTrue(aQual.averageQuality() > 70.0, "Successful model should have high quality, got: " + aQual.averageQuality());

        // model-c: 1 execution, 0 success, averageQuality MUST be null (no contamination)
        assertNotNull(cQual);
        assertEquals(0L, cQual.successCount());
        assertEquals(0.0, cQual.successRate(), 0.001);
        assertNull(cQual.averageQuality(), "Failed model averageQuality must be null, not 0");
        assertNull(cQual.qualityPassRate(), "Failed model qualityPassRate must be null (no successful denominator)");
    }

    @Test
    void allFailedExecutions_haveNullAverageQuality() {
        // All 3 executions fail → averageQuality must be null, successRate must be 0
        SequentialInvoker allFailInvoker = new SequentialInvoker(List.of(
                BenchmarkRawResponse.failure("TimeoutException", "T1", 3000L),
                BenchmarkRawResponse.failure("TimeoutException", "T2", 3000L),
                BenchmarkRawResponse.failure("TimeoutException", "T3", 3000L)
        ));

        BenchmarkCase c = new BenchmarkCase("c1", TaskType.STRUCTURED_GENERATION, "agent", "p",
                new BenchmarkExpectedOutput("{}", List.of("id"), List.of(), List.of(), List.of(), null, null, null, null),
                GroundTruthLevel.DETERMINISTIC, Set.of());
        BenchmarkDataset dataset = new BenchmarkDataset("ds", "v1", List.of(c));

        BenchmarkRunner testRunner = buildRunner(allFailInvoker, properties);
        BenchmarkReport report = testRunner.run(dataset, List.of("model-a", "model-b", "model-c"));

        for (ModelOverallQuality qual : report.perModelQuality().values()) {
            assertEquals(0L, qual.successCount(), "successCount must be 0");
            assertEquals(0.0, qual.successRate(), 0.001, "successRate must be 0");
            assertNull(qual.averageQuality(), "averageQuality must be null when all failed");
            assertNull(qual.qualityPassRate(), "qualityPassRate must be null when all failed (no NaN/Infinity)");
        }

        // Overall success rate
        assertEquals(0.0, report.executionSuccessRate(), 0.001);
    }

    @Test
    void executionSuccessAndQualityPassRates_haveCorrectDenominators() {
        // 2 success (score≈90, score≈90) + 1 failure
        // executionSuccessRate = 2/3
        // successRate per model = 1/1 for success models, 0/1 for failure model
        // qualityPassRate = passCount / successCount (NOT / executions)
        SequentialInvoker invoker = new SequentialInvoker(List.of(
                BenchmarkRawResponse.success("{\"name\": \"X\", \"id\": \"1\"}", 100L, 50L, 20L, 70L),
                BenchmarkRawResponse.success("{\"name\": \"Y\", \"id\": \"2\"}", 110L, 50L, 20L, 70L),
                BenchmarkRawResponse.failure("IOError", "Connection refused", 200L)
        ));

        BenchmarkCase c = new BenchmarkCase("c1", TaskType.STRUCTURED_GENERATION, "agent", "p",
                new BenchmarkExpectedOutput("{}", List.of("name"), List.of(), List.of(), List.of(), null, null, null, null),
                GroundTruthLevel.DETERMINISTIC, Set.of());
        BenchmarkDataset dataset = new BenchmarkDataset("ds", "v1", List.of(c));

        BenchmarkRunner testRunner = buildRunner(invoker, properties);
        BenchmarkReport report = testRunner.run(dataset, List.of("model-a", "model-b", "model-c"));

        // Overall reliability
        assertEquals(3, report.modelExecutions());
        assertEquals(2.0 / 3, report.executionSuccessRate(), 0.001);

        // model-c failure: successRate=0, qualityPassRate=null (NaN-safe)
        ModelOverallQuality cQual = report.perModelQuality().get("model-c");
        assertNotNull(cQual);
        assertEquals(0.0, cQual.successRate(), 0.001);
        assertNull(cQual.qualityPassRate(), "qualityPassRate must be null when no successful executions");
        assertNull(cQual.averageQuality(), "averageQuality must be null when no successful executions");

        // model-a success: successRate=1.0, qualityPassRate based on successCount
        ModelOverallQuality aQual = report.perModelQuality().get("model-a");
        assertNotNull(aQual);
        assertEquals(1.0, aQual.successRate(), 0.001);
        assertNotNull(aQual.qualityPassRate());
        // passRate denominator is successCount (=1), not executions (=1) — same here, but semantics are correct
    }

    // =========================================================================
    // Phase 8.2 — Configuration Validation Fail-Fast
    // =========================================================================

    @Test
    void enabledBenchmark_invalidMaxCases_failsFastBeforeInvocation() {
        CountingFakeInvoker countingInvoker = new CountingFakeInvoker();
        BenchmarkExecutionProperties invalidProps = new BenchmarkExecutionProperties();
        invalidProps.setEnabled(true);
        invalidProps.setMaxCases(0);   // invalid: must be >= 1

        BenchmarkRunner invalidRunner = buildRunner(countingInvoker, invalidProps);
        BenchmarkDataset dataset = createDataset(3);

        assertThrows(IllegalArgumentException.class,
                () -> invalidRunner.run(dataset, List.of("qwen3.7-flash")),
                "maxCases=0 must throw IllegalArgumentException");

        assertEquals(0, countingInvoker.getInvocationCount(),
                "No model invocations must occur before validation failure");
    }

    @Test
    void enabledBenchmark_invalidTimeout_failsFastBeforeInvocation() {
        CountingFakeInvoker countingInvoker = new CountingFakeInvoker();
        BenchmarkExecutionProperties invalidProps = new BenchmarkExecutionProperties();
        invalidProps.setEnabled(true);
        invalidProps.setRequestTimeoutSeconds(0);  // invalid: must be > 0

        BenchmarkRunner invalidRunner = buildRunner(countingInvoker, invalidProps);
        BenchmarkDataset dataset = createDataset(3);

        assertThrows(IllegalArgumentException.class,
                () -> invalidRunner.run(dataset, List.of("qwen3.7-flash")),
                "requestTimeoutSeconds=0 must throw IllegalArgumentException");

        assertEquals(0, countingInvoker.getInvocationCount(),
                "No model invocations must occur before validation failure");
    }

    @Test
    void enabledBenchmark_negativeQualityTolerance_failsFastBeforeInvocation() {
        CountingFakeInvoker countingInvoker = new CountingFakeInvoker();
        BenchmarkExecutionProperties invalidProps = new BenchmarkExecutionProperties();
        invalidProps.setEnabled(true);
        invalidProps.setQualityTieTolerance(-1.0);  // invalid: must be >= 0

        BenchmarkRunner invalidRunner = buildRunner(countingInvoker, invalidProps);
        BenchmarkDataset dataset = createDataset(3);

        assertThrows(IllegalArgumentException.class,
                () -> invalidRunner.run(dataset, List.of("qwen3.7-flash")),
                "qualityTieTolerance=-1.0 must throw IllegalArgumentException");

        assertEquals(0, countingInvoker.getInvocationCount(),
                "No model invocations must occur before validation failure");
    }

    @Test
    void disabledBenchmark_withAllInvalidProperties_stillReturnsDisabledWithoutException() {
        // enabled=false must WIN over invalid properties — disabled gate is absolute first priority.
        // No IllegalArgumentException should be thrown, and no model invocations made.
        CountingFakeInvoker countingInvoker = new CountingFakeInvoker();
        BenchmarkExecutionProperties invalidProps = new BenchmarkExecutionProperties();
        invalidProps.setEnabled(false);          // gate: disabled
        invalidProps.setMaxCases(0);             // would fail validate()
        invalidProps.setRequestTimeoutSeconds(0); // would fail validate()
        invalidProps.setQualityTieTolerance(-1); // would fail validate()

        BenchmarkRunner disabledRunner = buildRunner(countingInvoker, invalidProps);
        BenchmarkDataset dataset = createDataset(3);

        BenchmarkReport report = assertDoesNotThrow(
                () -> disabledRunner.run(dataset, List.of("qwen3.7-flash")),
                "disabled benchmark must not throw even with invalid properties");

        assertEquals(BenchmarkRunStatus.DISABLED, report.runStatus(),
                "Report must show DISABLED status");
        assertEquals(0, countingInvoker.getInvocationCount(),
                "No model invocations must occur when disabled");
    }

    // =========================================================================
    // Existing tests (unchanged semantics, adapted for new fields)
    // =========================================================================

    @Test
    void test1_benchmarkMatrix_executesAllCaseAndModelCombinations() {
        BenchmarkDataset dataset = createDataset(3);
        List<String> models = List.of("qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max");

        BenchmarkReport report = runner.run(dataset, models);

        assertEquals(BenchmarkRunStatus.COMPLETED, report.runStatus());
        assertEquals(3, report.totalCases());
        assertEquals(3, report.executedCases());
        assertEquals(9, report.modelExecutions());
        assertEquals(1.0, report.executionSuccessRate(), 0.001);
        assertEquals(3, report.perModelQuality().size());
        assertTrue(report.perModelQuality().containsKey("qwen3.7-flash"));
        assertTrue(report.perModelQuality().containsKey("qwen3.7-plus"));
        assertTrue(report.perModelQuality().containsKey("qwen3.8-max"));
    }

    @Test
    void test2_failureIsolation_singleModelFailureDoesNotHaltBenchmark() {
        fakeInvoker.setSimulateFailureModel("qwen3.8-max");
        BenchmarkDataset dataset = createDataset(2);
        List<String> models = List.of("qwen3.7-flash", "qwen3.8-max");

        BenchmarkReport report = runner.run(dataset, models);

        assertEquals(BenchmarkRunStatus.COMPLETED, report.runStatus());
        assertEquals(4, report.modelExecutions());
        assertEquals(0.50, report.executionSuccessRate(), 0.001);

        ModelOverallQuality flashQual = report.perModelQuality().get("qwen3.7-flash");
        assertNotNull(flashQual);
        assertEquals(2L, flashQual.successCount());
        assertEquals(1.0, flashQual.successRate(), 0.001);

        ModelOverallQuality maxQual = report.perModelQuality().get("qwen3.8-max");
        assertNotNull(maxQual);
        assertEquals(0L, maxQual.successCount());
        assertEquals(0.0, maxQual.successRate(), 0.001);
        assertNull(maxQual.averageQuality(), "Failed model must have null averageQuality");
    }

    @Test
    void test12_taskTypeMatrix_aggregatesScoresPerTaskTypeAndModel() {
        BenchmarkCase c1 = new BenchmarkCase("c1", TaskType.STRUCTURED_GENERATION, "agent", "prompt",
                new BenchmarkExpectedOutput("{}", List.of("id"), List.of(), List.of(), List.of(), null, null, null, null),
                GroundTruthLevel.DETERMINISTIC, Set.of());
        BenchmarkCase c2 = new BenchmarkCase("c2", TaskType.DRAWIO_GENERATION, "agent", "prompt",
                new BenchmarkExpectedOutput(null, List.of(), List.of(), List.of("Box"), List.of(), 1, 0, null, null),
                GroundTruthLevel.DETERMINISTIC, Set.of());

        BenchmarkDataset dataset = new BenchmarkDataset("ds-matrix", "v1", List.of(c1, c2));
        List<String> models = List.of("qwen3.7-flash", "qwen3.7-plus");

        BenchmarkReport report = runner.run(dataset, models);

        assertNotNull(report.taskTypeModelMatrix().get(TaskType.STRUCTURED_GENERATION));
        assertNotNull(report.taskTypeModelMatrix().get(TaskType.DRAWIO_GENERATION));
    }

    @Test
    void test15_emptyDataset_returnsNoDataStatus() {
        BenchmarkReport report = runner.run(new BenchmarkDataset("empty", "v1", List.of()), List.of("m1"));

        assertEquals(BenchmarkRunStatus.NO_DATA, report.runStatus());
        assertEquals(0, report.totalCases());
        assertEquals(0, report.modelExecutions());
        assertEquals(0.0, report.executionSuccessRate());
        assertTrue(report.caseEvaluations().isEmpty());
    }

    @Test
    void test17_credentialsNotSerialized_verifiesNoSecretLeaksInReport() {
        BenchmarkDataset dataset = createDataset(1);
        BenchmarkReport report = runner.run(dataset, List.of("qwen3.7-flash"));

        String reportStr = report.toString();
        assertFalse(reportStr.contains("apiKey"));
        assertFalse(reportStr.contains("Bearer"));
        assertFalse(reportStr.contains("Authorization"));
        assertFalse(reportStr.contains("sk-"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BenchmarkRunner buildRunner(BenchmarkModelInvoker invoker, BenchmarkExecutionProperties props) {
        ModelRoutingService legacyRouter = Mockito.mock(ModelRoutingService.class);
        when(legacyRouter.route(any())).thenReturn(
                new ModelRoutingService.Decision("qwen3.7-plus", "TEST", 2, "Test", Map.of(), List.of(), List.of())
        );
        return new BenchmarkRunner(
                invoker,
                List.of(new StructuredResponseQualityEvaluator(), new DrawioResponseQualityEvaluator(), new TextResponseQualityEvaluator()),
                new RoutingQualityEvaluator(),
                Mockito.mock(ModelCatalogService.class),
                new WeightedModelScorer(new ModelScoringProperties()),
                legacyRouter,
                Mockito.mock(RoutingContextFactory.class),
                Mockito.mock(RoutingRequirementService.class),
                Mockito.mock(ModelConstraintFilteringService.class),
                Mockito.mock(DynamicModelRankingService.class),
                props
        );
    }

    private BenchmarkDataset createDataset(int count) {
        List<BenchmarkCase> cases = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cases.add(new BenchmarkCase(
                    "case-" + i,
                    TaskType.STRUCTURED_GENERATION,
                    "agent",
                    "Generate JSON for case " + i,
                    new BenchmarkExpectedOutput("{}", List.of("name"), List.of(), List.of(), List.of(), null, null, null, null),
                    GroundTruthLevel.DETERMINISTIC,
                    Set.of("test")
            ));
        }
        return new BenchmarkDataset("test-ds", "v1", cases);
    }

    // =========================================================================
    // Fake Invokers
    // =========================================================================

    /** Deterministic fake — optional simulated failure for specific model names. */
    private static class FakeBenchmarkModelInvoker implements BenchmarkModelInvoker {
        private String simulateFailureModel;

        void setSimulateFailureModel(String model) {
            this.simulateFailureModel = model;
        }

        @Override
        public BenchmarkRawResponse invoke(String modelName, BenchmarkCase benchmarkCase) {
            if (modelName.equalsIgnoreCase(simulateFailureModel)) {
                return BenchmarkRawResponse.failure("SimulatedTimeoutException", "Simulated timeout", 1000L);
            }
            if (benchmarkCase.taskType() == TaskType.STRUCTURED_GENERATION) {
                return BenchmarkRawResponse.success("{\"name\": \"Item\", \"id\": \"123\"}", 100L, 50L, 20L, 70L);
            } else if (benchmarkCase.taskType() == TaskType.DRAWIO_GENERATION) {
                String xml = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Box\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>";
                return BenchmarkRawResponse.success(xml, 150L, 80L, 50L, 130L);
            } else {
                return BenchmarkRawResponse.success("Standard text response for " + benchmarkCase.caseId(), 80L, 40L, 20L, 60L);
            }
        }
    }

    /** Invoker that counts total invocations — used to verify the enabled gate produces zero calls. */
    private static class CountingFakeInvoker implements BenchmarkModelInvoker {
        private final AtomicInteger count = new AtomicInteger(0);

        int getInvocationCount() {
            return count.get();
        }

        @Override
        public BenchmarkRawResponse invoke(String modelName, BenchmarkCase benchmarkCase) {
            count.incrementAndGet();
            return BenchmarkRawResponse.success("{\"name\": \"ok\"}", 50L, 10L, 10L, 20L);
        }
    }

    /** Returns a pre-defined sequence of responses in order, regardless of model name. */
    private static class SequentialInvoker implements BenchmarkModelInvoker {
        private final List<BenchmarkRawResponse> responses;
        private int index = 0;

        SequentialInvoker(List<BenchmarkRawResponse> responses) {
            this.responses = responses;
        }

        @Override
        public BenchmarkRawResponse invoke(String modelName, BenchmarkCase benchmarkCase) {
            if (index < responses.size()) {
                return responses.get(index++);
            }
            return BenchmarkRawResponse.failure("IndexOutOfBounds", "No more responses configured", 0L);
        }
    }

    /** Fails on a specific model after N successful invocations. */
    private static class PartialFailInvoker implements BenchmarkModelInvoker {
        private final Map<String, Integer> failAfter;
        private final String failModelName;
        private final Map<String, Integer> callCounts = new HashMap<>();

        PartialFailInvoker(Map<String, Integer> failAfter, String failModelName) {
            this.failAfter = failAfter;
            this.failModelName = failModelName;
        }

        @Override
        public BenchmarkRawResponse invoke(String modelName, BenchmarkCase benchmarkCase) {
            int calls = callCounts.merge(modelName, 1, Integer::sum);
            Integer threshold = failAfter.get(modelName);
            if (threshold != null && calls > threshold) {
                return BenchmarkRawResponse.failure("TimeoutException", "Timed out", 3000L);
            }
            return BenchmarkRawResponse.success("{\"name\": \"ok\"}", 100L, 50L, 20L, 70L);
        }
    }
}
