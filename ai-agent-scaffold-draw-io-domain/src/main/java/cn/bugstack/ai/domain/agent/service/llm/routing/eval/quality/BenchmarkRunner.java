package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContextFactory;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirementService;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScorer;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrator service for offline quality benchmarking against multiple LLM models.
 *
 * <p><strong>Safety Guarantee:</strong>
 * This runner is strictly executed on-demand in offline mode or during manual testing.
 * It is NEVER triggered automatically during application startup or online user requests.</p>
 */
@Slf4j
@Service
public class BenchmarkRunner {

    private final BenchmarkModelInvoker modelInvoker;
    private final List<ResponseQualityEvaluator> evaluators;
    private final RoutingQualityEvaluator qualityEvaluator;
    private final ModelCatalogService modelCatalogService;
    private final ModelScorer modelScorer;
    private final ModelRoutingService legacyRoutingService;
    private final RoutingContextFactory routingContextFactory;
    private final RoutingRequirementService requirementService;
    private final ModelConstraintFilteringService constraintFilteringService;
    private final DynamicModelRankingService dynamicRankingService;
    private final BenchmarkExecutionProperties properties;

    public BenchmarkRunner(
            BenchmarkModelInvoker modelInvoker,
            List<ResponseQualityEvaluator> evaluators,
            RoutingQualityEvaluator qualityEvaluator,
            ModelCatalogService modelCatalogService,
            ModelScorer modelScorer,
            ModelRoutingService legacyRoutingService,
            RoutingContextFactory routingContextFactory,
            RoutingRequirementService requirementService,
            ModelConstraintFilteringService constraintFilteringService,
            DynamicModelRankingService dynamicRankingService,
            BenchmarkExecutionProperties properties) {
        this.modelInvoker = modelInvoker;
        this.evaluators = evaluators != null ? evaluators : List.of();
        this.qualityEvaluator = qualityEvaluator != null ? qualityEvaluator : new RoutingQualityEvaluator();
        this.modelCatalogService = modelCatalogService;
        this.modelScorer = modelScorer;
        this.legacyRoutingService = legacyRoutingService;
        this.routingContextFactory = routingContextFactory;
        this.requirementService = requirementService;
        this.constraintFilteringService = constraintFilteringService;
        this.dynamicRankingService = dynamicRankingService;
        this.properties = properties != null ? properties : new BenchmarkExecutionProperties();
    }

    /**
     * Executes the benchmark suite across the specified dataset and models.
     *
     * @param dataset           The benchmark dataset to evaluate.
     * @param targetModelNames  Optional list of model names. If null or empty, discovers all enabled models from catalog.
     * @return Deterministic, immutable {@link BenchmarkReport}.
     */
    public BenchmarkReport run(BenchmarkDataset dataset, List<String> targetModelNames) {
        if (dataset == null || dataset.cases() == null || dataset.cases().isEmpty()) {
            return buildEmptyReport(dataset != null ? dataset.datasetId() : "empty", dataset != null ? dataset.version() : "v1");
        }

        // 1. Discover target models
        List<String> modelsToEvaluate;
        if (targetModelNames != null && !targetModelNames.isEmpty()) {
            modelsToEvaluate = targetModelNames.stream().sorted().toList();
        } else if (modelCatalogService != null) {
            modelsToEvaluate = modelCatalogService.getEnabledModels().stream()
                    .map(ModelProfile::modelName)
                    .sorted()
                    .toList();
        } else {
            modelsToEvaluate = List.of();
        }

        if (modelsToEvaluate.isEmpty()) {
            return buildEmptyReport(dataset.datasetId(), dataset.version());
        }

        int maxCases = properties.getMaxCases() > 0 ? properties.getMaxCases() : 30;
        List<BenchmarkCase> casesToRun = dataset.cases().stream().limit(maxCases).toList();

        long totalModelExecutions = 0;
        long successfulModelExecutions = 0;

        List<RoutingQualityEvaluation> caseEvaluations = new ArrayList<>();
        Map<String, List<BenchmarkModelResult>> perModelResults = new TreeMap<>();
        Map<TaskType, Map<String, List<Double>>> taskTypeMatrixAccumulator = new EnumMap<>(TaskType.class);

        // 2. Iterate each case and model
        for (BenchmarkCase bCase : casesToRun) {
            List<BenchmarkModelResult> caseModelResults = new ArrayList<>();

            for (String modelName : modelsToEvaluate) {
                totalModelExecutions++;
                BenchmarkRawResponse rawResp;
                try {
                    rawResp = modelInvoker.invoke(modelName, bCase);
                } catch (Exception e) {
                    log.warn("Model invoker threw exception for model [{}] on case [{}]: {}", modelName, bCase.caseId(), e.getMessage());
                    rawResp = BenchmarkRawResponse.failure(e.getClass().getSimpleName(), e.getMessage(), 0L);
                }

                if (rawResp.success()) {
                    successfulModelExecutions++;
                }

                // Evaluate quality
                ModelQualityScore qualityScore = evaluateQuality(bCase, rawResp);

                // Estimate cost
                Double estimatedCost = estimateCost(modelName, bCase, rawResp);

                BenchmarkModelResult modelResult = new BenchmarkModelResult(
                        bCase.caseId(),
                        modelName,
                        rawResp.success(),
                        rawResp.responseText(),
                        rawResp.latencyMillis(),
                        rawResp.errorType(),
                        estimatedCost,
                        rawResp.totalTokens(),
                        qualityScore
                );

                caseModelResults.add(modelResult);
                perModelResults.computeIfAbsent(modelName, k -> new ArrayList<>()).add(modelResult);

                if (modelResult.success() && modelResult.qualityScore() != null) {
                    TaskType tt = bCase.taskType() != null ? bCase.taskType() : TaskType.UNKNOWN;
                    taskTypeMatrixAccumulator
                            .computeIfAbsent(tt, k -> new TreeMap<>())
                            .computeIfAbsent(modelName, k -> new ArrayList<>())
                            .add(modelResult.qualityScore().totalScore());
                }
            }

            // Determine Legacy and Dynamic recommendations for this case
            String legacyModel = resolveLegacyModel(bCase);
            String dynamicModel = resolveDynamicModel(bCase);

            RoutingQualityEvaluation evaluation = qualityEvaluator.evaluate(
                    bCase,
                    caseModelResults,
                    dynamicModel,
                    legacyModel
            );
            caseEvaluations.add(evaluation);
        }

        // 3. Compute Aggregates
        Map<String, ModelOverallQuality> perModelQuality = computeModelQualityMap(perModelResults);
        Map<TaskType, Map<String, Double>> taskTypeModelMatrix = computeTaskTypeMatrix(taskTypeMatrixAccumulator);

        long dynamicBetter = 0;
        long legacyBetter = 0;
        long equivalent = 0;

        for (RoutingQualityEvaluation ev : caseEvaluations) {
            if (ev.dynamicQualityScore() != null && ev.legacyQualityScore() != null) {
                double diff = ev.dynamicQualityScore() - ev.legacyQualityScore();
                if (diff > properties.getQualityTieTolerance()) {
                    dynamicBetter++;
                } else if (diff < -properties.getQualityTieTolerance()) {
                    legacyBetter++;
                } else {
                    equivalent++;
                }
            }
        }

        RouterQualitySummary dynamicSummary = summarizeRouter(caseEvaluations, true);
        RouterQualitySummary legacySummary = summarizeRouter(caseEvaluations, false);

        List<String> advisoryRecommendations = generateBenchmarkRecommendations(dynamicSummary, taskTypeModelMatrix);

        double execSuccessRate = totalModelExecutions > 0 ? (double) successfulModelExecutions / totalModelExecutions : 0.0;

        return new BenchmarkReport(
                dataset.datasetId(),
                dataset.version(),
                dataset.cases().size(),
                casesToRun.size(),
                totalModelExecutions,
                execSuccessRate,
                perModelQuality,
                taskTypeModelMatrix,
                dynamicSummary,
                legacySummary,
                dynamicBetter,
                legacyBetter,
                equivalent,
                caseEvaluations,
                advisoryRecommendations,
                Instant.now()
        );
    }

    // =========================================================================
    // Internal Evaluation & Estimation
    // =========================================================================

    private ModelQualityScore evaluateQuality(BenchmarkCase bCase, BenchmarkRawResponse rawResponse) {
        if (!rawResponse.success()) {
            return ModelQualityScore.failed("Execution failed: " + rawResponse.errorMessage());
        }

        for (ResponseQualityEvaluator evaluator : evaluators) {
            if (evaluator.supports(bCase)) {
                try {
                    return evaluator.evaluate(bCase, rawResponse);
                } catch (Exception e) {
                    log.warn("Evaluator [{}] failed on case [{}]: {}", evaluator.getClass().getSimpleName(), bCase.caseId(), e.getMessage());
                }
            }
        }
        return ModelQualityScore.of(50.0, Map.of("DEFAULT", 50.0), true, List.of("No specialized evaluator found, applied baseline"));
    }

    private Double estimateCost(String modelName, BenchmarkCase bCase, BenchmarkRawResponse rawResp) {
        if (modelCatalogService == null) return null;
        Optional<ModelProfile> profileOpt = modelCatalogService.findByModelName(modelName);
        if (profileOpt.isEmpty()) return null;

        ModelProfile profile = profileOpt.get();
        if (profile.pricing() == null || profile.pricing().inputPerMillionTokens() == null || profile.pricing().outputPerMillionTokens() == null) {
            return null;
        }

        long inputTokens = (rawResp.promptTokens() != null && rawResp.promptTokens() > 0)
                ? rawResp.promptTokens()
                : (bCase.prompt() != null ? Math.max(1, bCase.prompt().length() / 4) : 25L);
        long outputTokens = (rawResp.completionTokens() != null && rawResp.completionTokens() > 0)
                ? rawResp.completionTokens()
                : (rawResp.responseText() != null ? Math.max(1, rawResp.responseText().length() / 4) : 50L);

        double costIn = (inputTokens / 1_000_000.0) * profile.pricing().inputPerMillionTokens().doubleValue();
        double costOut = (outputTokens / 1_000_000.0) * profile.pricing().outputPerMillionTokens().doubleValue();
        return Math.round((costIn + costOut) * 100000.0) / 100000.0;
    }

    private String resolveLegacyModel(BenchmarkCase bCase) {
        if (legacyRoutingService == null) return null;
        try {
            LlmRequest req = LlmRequest.builder()
                    .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText(bCase.prompt()))).build()))
                    .build();
            ModelRoutingService.Decision d = legacyRoutingService.route(req);
            return d != null ? d.model() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveDynamicModel(BenchmarkCase bCase) {
        if (routingContextFactory == null || requirementService == null
                || constraintFilteringService == null || dynamicRankingService == null) {
            return null;
        }
        try {
            LlmRequest req = LlmRequest.builder()
                    .contents(List.of(Content.builder().role("user").parts(List.of(Part.fromText(bCase.prompt()))).build()))
                    .build();
            RoutingContext ctx = routingContextFactory.create(req, bCase.agentName(), "UNKNOWN", false, null);
            var reqOpt = requirementService.tryAnalyze(ctx);
            if (reqOpt.isEmpty()) return null;

            var filterRes = constraintFilteringService.filter(reqOpt.get());
            var rankRes = dynamicRankingService.rank(reqOpt.get(), filterRes);
            return rankRes.topCandidate().map(c -> c.model().modelName()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, ModelOverallQuality> computeModelQualityMap(Map<String, List<BenchmarkModelResult>> perModelResults) {
        Map<String, ModelOverallQuality> map = new TreeMap<>();
        for (Map.Entry<String, List<BenchmarkModelResult>> entry : perModelResults.entrySet()) {
            String model = entry.getKey();
            List<BenchmarkModelResult> list = entry.getValue();

            long executions = list.size();
            long successCount = 0;
            long passCount = 0;
            double qualitySum = 0;
            long qualityCount = 0;
            double latencySum = 0;
            double costSum = 0;
            long costCount = 0;

            for (BenchmarkModelResult r : list) {
                if (r.success()) {
                    successCount++;
                    latencySum += r.latencyMillis();
                }
                if (r.qualityScore() != null) {
                    if (r.qualityScore().passed()) passCount++;
                    qualitySum += r.qualityScore().totalScore();
                    qualityCount++;
                }
                if (r.estimatedCost() != null) {
                    costSum += r.estimatedCost();
                    costCount++;
                }
            }

            Double avgQuality = qualityCount > 0 ? qualitySum / qualityCount : null;
            Double avgLatency = successCount > 0 ? latencySum / successCount : null;
            Double avgCost = costCount > 0 ? costSum / costCount : null;
            double passRate = executions > 0 ? (double) passCount / executions : 0.0;

            map.put(model, new ModelOverallQuality(executions, successCount, passCount, passRate, avgQuality, avgLatency, avgCost));
        }
        return map;
    }

    private Map<TaskType, Map<String, Double>> computeTaskTypeMatrix(Map<TaskType, Map<String, List<Double>>> accum) {
        Map<TaskType, Map<String, Double>> matrix = new TreeMap<>(Comparator.comparing(Enum::name));
        for (Map.Entry<TaskType, Map<String, List<Double>>> ttEntry : accum.entrySet()) {
            Map<String, Double> modelAverages = new TreeMap<>();
            for (Map.Entry<String, List<Double>> mEntry : ttEntry.getValue().entrySet()) {
                List<Double> scores = mEntry.getValue();
                double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                modelAverages.put(mEntry.getKey(), avg);
            }
            matrix.put(ttEntry.getKey(), modelAverages);
        }
        return matrix;
    }

    private RouterQualitySummary summarizeRouter(List<RoutingQualityEvaluation> evaluations, boolean dynamic) {
        long total = evaluations.size();
        long opt = 0;
        long equiv = 0;
        long over = 0;
        long under = 0;
        double regretSum = 0;
        long regretCount = 0;

        for (RoutingQualityEvaluation ev : evaluations) {
            RoutingQualityClassification c = dynamic ? ev.dynamicClassification() : ev.legacyClassification();
            Double r = dynamic ? ev.dynamicRegret() : ev.legacyRegret();

            if (c == RoutingQualityClassification.OPTIMAL) opt++;
            else if (c == RoutingQualityClassification.QUALITY_EQUIVALENT) equiv++;
            else if (c == RoutingQualityClassification.POTENTIAL_OVER_ROUTING) over++;
            else if (c == RoutingQualityClassification.UNDER_ROUTING) under++;

            if (r != null) {
                regretSum += r;
                regretCount++;
            }
        }

        Double avgRegret = regretCount > 0 ? regretSum / regretCount : null;
        double optRate = total > 0 ? (double) opt / total : 0.0;
        double equivRate = total > 0 ? (double) equiv / total : 0.0;
        double overRate = total > 0 ? (double) over / total : 0.0;
        double underRate = total > 0 ? (double) under / total : 0.0;

        return new RouterQualitySummary(total, avgRegret, opt, equiv, over, under, optRate, equivRate, overRate, underRate);
    }

    private List<String> generateBenchmarkRecommendations(RouterQualitySummary dynamicSummary, Map<TaskType, Map<String, Double>> matrix) {
        List<String> recs = new ArrayList<>();
        if (dynamicSummary.overRoutingRate() > 0.30) {
            recs.add("POTENTIAL_COST_OVER_ROUTING: Dynamic Router exhibits high potential over-routing rate ("
                    + String.format("%.1f%%", dynamicSummary.overRoutingRate() * 100) + "). Consider tuning cost preference or oversupply penalties.");
        }
        if (dynamicSummary.underRoutingRate() > 0.20) {
            recs.add("POTENTIAL_UNDER_ROUTING: Dynamic Router selected weaker models leading to quality deficit in ("
                    + String.format("%.1f%%", dynamicSummary.underRoutingRate() * 100) + ") of cases. Consider checking minimum capability requirements.");
        }
        return recs;
    }

    private BenchmarkReport buildEmptyReport(String datasetId, String version) {
        return new BenchmarkReport(
                datasetId,
                version,
                0,
                0,
                0,
                0.0,
                Map.of(),
                Map.of(),
                new RouterQualitySummary(0, null, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0),
                new RouterQualitySummary(0, null, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0),
                0,
                0,
                0,
                List.of(),
                List.of("INSUFFICIENT_BENCHMARK_CASES: No benchmark cases or models available to execute."),
                Instant.now()
        );
    }
}
