package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogProperties;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.WeightedModelScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class BenchmarkRunnerTest {

    private BenchmarkRunner runner;
    private FakeBenchmarkModelInvoker fakeInvoker;
    private ModelCatalogService modelCatalogService;

    @BeforeEach
    void setUp() {
        this.fakeInvoker = new FakeBenchmarkModelInvoker();
        StructuredResponseQualityEvaluator structEval = new StructuredResponseQualityEvaluator();
        DrawioResponseQualityEvaluator drawioEval = new DrawioResponseQualityEvaluator();
        TextResponseQualityEvaluator textEval = new TextResponseQualityEvaluator();
        List<ResponseQualityEvaluator> evaluators = List.of(structEval, drawioEval, textEval);

        RoutingQualityEvaluator qualityEvaluator = new RoutingQualityEvaluator();
        this.modelCatalogService = Mockito.mock(ModelCatalogService.class);
        WeightedModelScorer scorer = new WeightedModelScorer(new cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScoringProperties());
        ModelRoutingService legacyRouter = Mockito.mock(ModelRoutingService.class);

        when(legacyRouter.route(any())).thenReturn(
                new ModelRoutingService.Decision("qwen3.7-plus", "TEST", 2, "Test", Map.of(), List.of(), List.of())
        );

        RoutingContextFactory ctxFactory = Mockito.mock(RoutingContextFactory.class);
        RoutingRequirementService reqService = Mockito.mock(RoutingRequirementService.class);
        ModelConstraintFilteringService filterService = Mockito.mock(ModelConstraintFilteringService.class);
        DynamicModelRankingService rankingService = Mockito.mock(DynamicModelRankingService.class);

        BenchmarkExecutionProperties properties = new BenchmarkExecutionProperties();
        properties.setMaxCases(30);

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

    @Test
    void test1_benchmarkMatrix_executesAllCaseAndModelCombinations() {
        // 3 cases × 3 models = 9 executions
        BenchmarkDataset dataset = createDataset(3);
        List<String> models = List.of("qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max");

        BenchmarkReport report = runner.run(dataset, models);

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

        assertEquals(4, report.modelExecutions());
        assertEquals(0.50, report.executionSuccessRate(), 0.001);

        ModelOverallQuality flashQual = report.perModelQuality().get("qwen3.7-flash");
        assertNotNull(flashQual);
        assertEquals(2L, flashQual.successCount());

        ModelOverallQuality maxQual = report.perModelQuality().get("qwen3.8-max");
        assertNotNull(maxQual);
        assertEquals(0L, maxQual.successCount());
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
    void test15_emptyDataset_returnsSafeEmptyReport() {
        BenchmarkReport report = runner.run(new BenchmarkDataset("empty", "v1", List.of()), List.of("m1"));

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

    /**
     * Fake invoker providing deterministic responses without network calls.
     */
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
}
