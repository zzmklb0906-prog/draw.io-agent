package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ConstraintReason;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RankedCandidateSnapshot;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RequirementSnapshot;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationFlag;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecord;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Functional, side-effect free analyzer for offline routing evaluation datasets.
 *
 * <p><strong>Architectural Boundary:</strong>
 * This analyzer only measures, aggregates, and diagnoses routing decisions.
 * It NEVER mutates configurations, weights, catalog data, or policies.</p>
 */
@Slf4j
@Service
public class RoutingEvaluationAnalyzer {

    private final RoutingEvaluationAnalysisProperties properties;

    public RoutingEvaluationAnalyzer() {
        this(new RoutingEvaluationAnalysisProperties());
    }

    public RoutingEvaluationAnalyzer(RoutingEvaluationAnalysisProperties properties) {
        this.properties = properties != null ? properties : new RoutingEvaluationAnalysisProperties();
    }

    /**
     * Analyzes a collection of {@link RoutingEvaluationRecord}s and produces a deterministic report.
     */
    public RoutingEvaluationAnalysisReport analyze(List<RoutingEvaluationRecord> records) {
        if (records == null || records.isEmpty()) {
            return buildEmptyReport();
        }

        List<RoutingEvaluationRecord> cleanRecords = records.stream().filter(Objects::nonNull).toList();
        if (cleanRecords.isEmpty()) {
            return buildEmptyReport();
        }

        long totalRecords = cleanRecords.size();
        long comparableRecords = 0;
        long agreementCount = 0;
        long disagreementCount = 0;
        long noRecommendationCount = 0;

        long actualHardRejectedCount = 0;
        long catalogLookupFailureCount = 0;
        long pricingUnavailableCount = 0;
        long costComparisonUnavailableCount = 0;

        Map<String, Long> recModelDist = new TreeMap<>();
        Map<String, Long> actModelDist = new TreeMap<>();
        Map<ConstraintReason, Long> hardRejectionDist = new TreeMap<>();

        // Groupings for TaskType and Agent
        Map<TaskType, List<RoutingEvaluationRecord>> taskTypeGroups = new EnumMap<>(TaskType.class);
        Map<String, List<RoutingEvaluationRecord>> agentGroups = new TreeMap<>();

        List<Double> validMargins = new ArrayList<>();
        List<Double> validCostDeltas = new ArrayList<>();
        List<RequirementSnapshot> validReqSnapshots = new ArrayList<>();

        // Pair competition accumulator: key -> {count, totalMargin, validMarginCount}
        Map<String, PairAccumulator> pairAccumulators = new HashMap<>();

        for (RoutingEvaluationRecord r : cleanRecords) {
            // Recommendation and Actual presence
            if (r.recommendedModel() == null) {
                noRecommendationCount++;
            } else {
                recModelDist.merge(r.recommendedModel(), 1L, Long::sum);
            }

            if (r.actualModel() != null) {
                actModelDist.merge(r.actualModel(), 1L, Long::sum);
            }

            // Agreement calculation
            if (r.actualModel() != null && r.recommendedModel() != null && r.matched() != null) {
                comparableRecords++;
                if (Boolean.TRUE.equals(r.matched())) {
                    agreementCount++;
                } else {
                    disagreementCount++;
                }
            }

            // Flag counts
            Set<RoutingEvaluationFlag> flags = r.flags() != null ? r.flags() : Set.of();
            if (flags.contains(RoutingEvaluationFlag.ACTUAL_MODEL_HARD_REJECTED)) {
                actualHardRejectedCount++;
            }
            if (flags.contains(RoutingEvaluationFlag.CATALOG_LOOKUP_FAILED)) {
                catalogLookupFailureCount++;
            }
            if (flags.contains(RoutingEvaluationFlag.PRICING_UNAVAILABLE)) {
                pricingUnavailableCount++;
            }
            if (flags.contains(RoutingEvaluationFlag.COST_COMPARISON_UNAVAILABLE)) {
                costComparisonUnavailableCount++;
            }

            // Hard Rejection Reasons
            if (r.rejectedReasons() != null) {
                for (List<ConstraintReason> reasons : r.rejectedReasons().values()) {
                    if (reasons != null) {
                        for (ConstraintReason reason : reasons) {
                            if (reason != null) {
                                hardRejectionDist.merge(reason, 1L, Long::sum);
                            }
                        }
                    }
                }
            }

            // Groupings
            TaskType tt = r.taskType() != null ? r.taskType() : TaskType.UNKNOWN;
            taskTypeGroups.computeIfAbsent(tt, k -> new ArrayList<>()).add(r);

            String agent = r.agentName() != null ? r.agentName() : "unknown";
            agentGroups.computeIfAbsent(agent, k -> new ArrayList<>()).add(r);

            // Margin collection
            if (r.scoreMargin() != null) {
                validMargins.add(r.scoreMargin());
            }

            // Cost delta collection
            if (r.costDelta() != null && r.estimatedActualCost() != null && r.estimatedRecommendedCost() != null) {
                validCostDeltas.add(r.costDelta());
            }

            // Requirement collection
            if (r.requirementSnapshot() != null) {
                validReqSnapshots.add(r.requirementSnapshot());
            }

            // Model competition pair extraction (top1 vs top2)
            if (r.topCandidates() != null && r.topCandidates().size() >= 2) {
                RankedCandidateSnapshot top1 = r.topCandidates().get(0);
                RankedCandidateSnapshot top2 = r.topCandidates().get(1);
                if (top1 != null && top2 != null && top1.modelId() != null && top2.modelId() != null) {
                    String pairKey = top1.modelId() + " > " + top2.modelId();
                    PairAccumulator acc = pairAccumulators.computeIfAbsent(pairKey,
                            k -> new PairAccumulator(top1.modelId(), top2.modelId()));
                    acc.count++;
                    if (r.scoreMargin() != null) {
                        acc.totalMargin += r.scoreMargin();
                        acc.marginCount++;
                    }
                }
            }
        }

        double agreementRate = comparableRecords > 0 ? (double) agreementCount / comparableRecords : 0.0;
        double actualHardRejectedRate = totalRecords > 0 ? (double) actualHardRejectedCount / totalRecords : 0.0;
        double catalogLookupFailureRate = totalRecords > 0 ? (double) catalogLookupFailureCount / totalRecords : 0.0;

        // Compute Sub-analyses
        Map<TaskType, TaskTypeAnalysis> taskTypeAnalysisMap = computeTaskTypeAnalysis(taskTypeGroups);
        Map<String, AgentAnalysis> agentAnalysisMap = computeAgentAnalysis(agentGroups);
        ScoreMarginStatistics marginStats = computeScoreMarginStatistics(validMargins);
        CostDeltaStatistics costStats = computeCostDeltaStatistics(validCostDeltas);
        RequirementDimensionStatistics reqStats = computeRequirementStatistics(validReqSnapshots);
        List<ModelCompetitionPair> competitionPairs = computeCompetitionPairs(pairAccumulators);

        boolean insufficientSample = totalRecords < properties.getMinSampleSize();
        List<RoutingCalibrationRecommendation> recommendations = generateRecommendations(
                totalRecords,
                insufficientSample,
                catalogLookupFailureRate,
                pricingUnavailableCount,
                actualHardRejectedRate,
                marginStats,
                costStats,
                reqStats
        );

        return new RoutingEvaluationAnalysisReport(
                totalRecords,
                comparableRecords,
                agreementCount,
                disagreementCount,
                noRecommendationCount,
                agreementRate,
                actualHardRejectedCount,
                actualHardRejectedRate,
                catalogLookupFailureCount,
                catalogLookupFailureRate,
                pricingUnavailableCount,
                costComparisonUnavailableCount,
                recModelDist,
                actModelDist,
                taskTypeAnalysisMap,
                agentAnalysisMap,
                hardRejectionDist,
                marginStats,
                costStats,
                reqStats,
                competitionPairs,
                recommendations,
                insufficientSample
        );
    }

    // =========================================================================
    // Statistics Calculations
    // =========================================================================

    private Map<TaskType, TaskTypeAnalysis> computeTaskTypeAnalysis(Map<TaskType, List<RoutingEvaluationRecord>> groups) {
        Map<TaskType, TaskTypeAnalysis> result = new TreeMap<>(Comparator.comparing(Enum::name));
        for (Map.Entry<TaskType, List<RoutingEvaluationRecord>> entry : groups.entrySet()) {
            TaskType tt = entry.getKey();
            List<RoutingEvaluationRecord> list = entry.getValue();

            long total = list.size();
            long comp = 0;
            long agree = 0;
            long disagree = 0;
            Map<String, Long> recDist = new TreeMap<>();
            Map<String, Long> actDist = new TreeMap<>();
            double costSum = 0;
            long costCount = 0;

            for (RoutingEvaluationRecord r : list) {
                if (r.recommendedModel() != null) {
                    recDist.merge(r.recommendedModel(), 1L, Long::sum);
                }
                if (r.actualModel() != null) {
                    actDist.merge(r.actualModel(), 1L, Long::sum);
                }
                if (r.actualModel() != null && r.recommendedModel() != null && r.matched() != null) {
                    comp++;
                    if (Boolean.TRUE.equals(r.matched())) agree++;
                    else disagree++;
                }
                if (r.costDelta() != null && r.estimatedActualCost() != null && r.estimatedRecommendedCost() != null) {
                    costSum += r.costDelta();
                    costCount++;
                }
            }

            double rate = comp > 0 ? (double) agree / comp : 0.0;
            Double avgCost = costCount > 0 ? costSum / costCount : null;

            result.put(tt, new TaskTypeAnalysis(total, comp, agree, disagree, rate, recDist, actDist, avgCost));
        }
        return result;
    }

    private Map<String, AgentAnalysis> computeAgentAnalysis(Map<String, List<RoutingEvaluationRecord>> groups) {
        Map<String, AgentAnalysis> result = new TreeMap<>();
        for (Map.Entry<String, List<RoutingEvaluationRecord>> entry : groups.entrySet()) {
            String agent = entry.getKey();
            List<RoutingEvaluationRecord> list = entry.getValue();

            long total = list.size();
            long comp = 0;
            long agree = 0;
            long disagree = 0;
            Map<String, Long> recDist = new TreeMap<>();
            Map<String, Long> actDist = new TreeMap<>();

            for (RoutingEvaluationRecord r : list) {
                if (r.recommendedModel() != null) {
                    recDist.merge(r.recommendedModel(), 1L, Long::sum);
                }
                if (r.actualModel() != null) {
                    actDist.merge(r.actualModel(), 1L, Long::sum);
                }
                if (r.actualModel() != null && r.recommendedModel() != null && r.matched() != null) {
                    comp++;
                    if (Boolean.TRUE.equals(r.matched())) agree++;
                    else disagree++;
                }
            }

            double rate = comp > 0 ? (double) agree / comp : 0.0;
            result.put(agent, new AgentAnalysis(total, comp, agree, disagree, rate, recDist, actDist));
        }
        return result;
    }

    private ScoreMarginStatistics computeScoreMarginStatistics(List<Double> margins) {
        if (margins.isEmpty()) {
            return new ScoreMarginStatistics(0, null, null, null, null, null, 0, 0.0);
        }

        List<Double> sorted = new ArrayList<>(margins);
        Collections.sort(sorted);
        int n = sorted.size();

        double sum = 0;
        long lowMarginCount = 0;
        for (Double m : sorted) {
            sum += m;
            if (m < properties.getLowMarginThreshold()) {
                lowMarginCount++;
            }
        }

        double avg = sum / n;
        double median = percentile(sorted, 50.0);
        double p25 = percentile(sorted, 25.0);
        double p75 = percentile(sorted, 75.0);
        double p90 = percentile(sorted, 90.0);
        double lowRate = (double) lowMarginCount / n;

        return new ScoreMarginStatistics(n, avg, median, p25, p75, p90, lowMarginCount, lowRate);
    }

    private CostDeltaStatistics computeCostDeltaStatistics(List<Double> costDeltas) {
        if (costDeltas.isEmpty()) {
            return new CostDeltaStatistics(0, null, null, 0, 0, 0, 0.0, 0.0, 0.0);
        }

        List<Double> sorted = new ArrayList<>(costDeltas);
        Collections.sort(sorted);
        int n = sorted.size();

        double sum = 0;
        long cheaper = 0;
        long moreExpensive = 0;
        long same = 0;

        for (Double delta : sorted) {
            sum += delta;
            if (delta < -1e-6) {
                cheaper++;
            } else if (delta > 1e-6) {
                moreExpensive++;
            } else {
                same++;
            }
        }

        double avg = sum / n;
        double median = percentile(sorted, 50.0);

        return new CostDeltaStatistics(
                n,
                avg,
                median,
                cheaper,
                moreExpensive,
                same,
                (double) cheaper / n,
                (double) moreExpensive / n,
                (double) same / n
        );
    }

    private RequirementDimensionStatistics computeRequirementStatistics(List<RequirementSnapshot> reqs) {
        if (reqs.isEmpty()) {
            return new RequirementDimensionStatistics(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        int n = reqs.size();
        double sumReasoning = 0;
        double sumInst = 0;
        double sumCoding = 0;
        double sumStruct = 0;
        double sumTool = 0;

        long highReasoning = 0;
        long highCoding = 0;
        long highStruct = 0;
        long highTool = 0;

        int thresh = properties.getHighDemandScoreThreshold();

        for (RequirementSnapshot req : reqs) {
            sumReasoning += req.reasoning();
            sumInst += req.instructionFollowing();
            sumCoding += req.coding();
            sumStruct += req.structuredOutput();
            sumTool += req.toolCalling();

            if (req.reasoning() >= thresh) highReasoning++;
            if (req.coding() >= thresh) highCoding++;
            if (req.structuredOutput() >= thresh) highStruct++;
            if (req.toolCalling() >= thresh) highTool++;
        }

        return new RequirementDimensionStatistics(
                n,
                sumReasoning / n,
                sumInst / n,
                sumCoding / n,
                sumStruct / n,
                sumTool / n,
                (double) highReasoning / n,
                (double) highCoding / n,
                (double) highStruct / n,
                (double) highTool / n
        );
    }

    private List<ModelCompetitionPair> computeCompetitionPairs(Map<String, PairAccumulator> map) {
        List<PairAccumulator> list = new ArrayList<>(map.values());
        // Sort deterministically: count descending -> top1 ascending -> top2 ascending
        list.sort((a, b) -> {
            int cmp = Long.compare(b.count, a.count);
            if (cmp != 0) return cmp;
            int c1 = a.top1.compareTo(b.top1);
            if (c1 != 0) return c1;
            return a.top2.compareTo(b.top2);
        });

        List<ModelCompetitionPair> result = new ArrayList<>();
        for (PairAccumulator acc : list) {
            double avgMargin = acc.marginCount > 0 ? acc.totalMargin / acc.marginCount : 0.0;
            result.add(new ModelCompetitionPair(acc.top1, acc.top2, acc.count, avgMargin));
        }
        return result;
    }

    private List<RoutingCalibrationRecommendation> generateRecommendations(
            long totalRecords,
            boolean insufficientSample,
            double catalogFailureRate,
            long pricingUnavailableCount,
            double actualHardRejectedRate,
            ScoreMarginStatistics marginStats,
            CostDeltaStatistics costStats,
            RequirementDimensionStatistics reqStats) {

        List<RoutingCalibrationRecommendation> recs = new ArrayList<>();

        if (insufficientSample) {
            recs.add(new RoutingCalibrationRecommendation(
                    "INSUFFICIENT_SAMPLE",
                    RoutingAnalysisSeverity.INFO,
                    RoutingAnalysisIssueCategory.DATA_QUALITY,
                    "sampleSize",
                    String.format("Total records (%d) is below the minimum threshold (%d)", totalRecords, properties.getMinSampleSize()),
                    "Accumulate more shadow evaluation records before drawing calibration conclusions."
            ));
            return recs; // Stop generating aggressive recommendations when sample is insufficient
        }

        // 1. Data Quality Diagnoses
        if (catalogFailureRate > properties.getCatalogFailureRateThreshold()) {
            recs.add(new RoutingCalibrationRecommendation(
                    "HIGH_CATALOG_LOOKUP_FAILURE_RATE",
                    RoutingAnalysisSeverity.WARNING,
                    RoutingAnalysisIssueCategory.DATA_QUALITY,
                    "catalogAvailability",
                    String.format("Catalog lookup failure rate (%.1f%%) exceeds warning threshold (%.1f%%)",
                            catalogFailureRate * 100, properties.getCatalogFailureRateThreshold() * 100),
                    "Investigate ModelCatalogService availability and ensure model identifiers in production align with catalog entries."
            ));
        }

        double pricingMissingRate = (double) pricingUnavailableCount / totalRecords;
        if (pricingMissingRate > properties.getPricingMissingRateThreshold()) {
            recs.add(new RoutingCalibrationRecommendation(
                    "HIGH_PRICING_METADATA_MISSING_RATE",
                    RoutingAnalysisSeverity.WARNING,
                    RoutingAnalysisIssueCategory.DATA_QUALITY,
                    "pricingMetadata",
                    String.format("Pricing missing rate (%.1f%%) exceeds warning threshold (%.1f%%)",
                            pricingMissingRate * 100, properties.getPricingMissingRateThreshold() * 100),
                    "Review model-catalog.yml to ensure input/output token pricing is configured for all active models."
            ));
        }

        // 2. Hard Reject Rate
        if (actualHardRejectedRate > properties.getActualHardRejectedRateThreshold()) {
            recs.add(new RoutingCalibrationRecommendation(
                    "HIGH_ACTUAL_HARD_REJECT_RATE",
                    RoutingAnalysisSeverity.WARNING,
                    RoutingAnalysisIssueCategory.ROUTING_BEHAVIOR,
                    "hardConstraintFiltering",
                    String.format("Actual legacy model hard rejected rate (%.1f%%) exceeds warning threshold (%.1f%%)",
                            actualHardRejectedRate * 100, properties.getActualHardRejectedRateThreshold() * 100),
                    "Review Legacy Router selection logic against Phase 4 Hard Constraints (context window, vision, tool support)."
            ));
        }

        // 3. Score Margin Diagnostic
        if (marginStats.lowMarginRate() > properties.getLowMarginRateThreshold()) {
            recs.add(new RoutingCalibrationRecommendation(
                    "LOW_SCORE_MARGIN_CONCENTRATION",
                    RoutingAnalysisSeverity.WARNING,
                    RoutingAnalysisIssueCategory.MODEL_CALIBRATION,
                    "scoreSeparation",
                    String.format("Low score margin rate (%.1f%%) exceeds warning threshold (%.1f%%)",
                            marginStats.lowMarginRate() * 100, properties.getLowMarginRateThreshold() * 100),
                    "Review model capability calibration scores and candidate separation to ensure distinct rank differentiation."
            ));
        }

        // 4. Cost Increase Diagnostic
        if (costStats.comparableCount() > 0 && costStats.moreExpensiveRate() > properties.getCostIncreaseRateThreshold()) {
            recs.add(new RoutingCalibrationRecommendation(
                    "HIGH_COST_INCREASE_RATE",
                    RoutingAnalysisSeverity.WARNING,
                    RoutingAnalysisIssueCategory.COST,
                    "costPreference",
                    String.format("Dynamic recommendation is more expensive in %.1f%% of comparable records (threshold: %.1f%%)",
                            costStats.moreExpensiveRate() * 100, properties.getCostIncreaseRateThreshold() * 100),
                    "Review cost preference weight and capability oversupply handling to verify whether expensive models are being over-selected for simple tasks."
            ));
        }

        // 5. Requirement Dimension Saturation
        if (reqStats.sampleCount() > 0) {
            double thresh = properties.getHighDemandRateThreshold();
            if (reqStats.highDemandStructuredOutputRate() > thresh) {
                recs.add(new RoutingCalibrationRecommendation(
                    "REQUIREMENT_DIMENSION_SATURATION",
                    RoutingAnalysisSeverity.INFO,
                    RoutingAnalysisIssueCategory.REQUIREMENT_CALIBRATION,
                    "structuredOutput",
                    String.format("Structured output requirement is high (>=%d) in %.1f%% of records",
                            properties.getHighDemandScoreThreshold(), reqStats.highDemandStructuredOutputRate() * 100),
                    "Review AgentRequirementPolicy and TaskType detector baselines for structured output demand."
                ));
            }
            if (reqStats.highDemandReasoningRate() > thresh) {
                recs.add(new RoutingCalibrationRecommendation(
                    "REQUIREMENT_DIMENSION_SATURATION",
                    RoutingAnalysisSeverity.INFO,
                    RoutingAnalysisIssueCategory.REQUIREMENT_CALIBRATION,
                    "reasoning",
                    String.format("Reasoning requirement is high (>=%d) in %.1f%% of records",
                            properties.getHighDemandScoreThreshold(), reqStats.highDemandReasoningRate() * 100),
                    "Review reasoning demand heuristic extraction to verify whether routine tasks are judged too demanding."
                ));
            }
        }

        // Deterministic sorting: Severity descending -> code ascending -> dimension ascending
        recs.sort((a, b) -> {
            int cmp = b.severity().compareTo(a.severity());
            if (cmp != 0) return cmp;
            int c1 = a.code().compareTo(b.code());
            if (c1 != 0) return c1;
            return a.dimension().compareTo(b.dimension());
        });

        return recs;
    }

    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0.0;
        if (sorted.size() == 1) return sorted.get(0);
        double rank = (p / 100.0) * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted.get(low);
        }
        double weight = rank - low;
        return sorted.get(low) * (1.0 - weight) + sorted.get(high) * weight;
    }

    private RoutingEvaluationAnalysisReport buildEmptyReport() {
        return new RoutingEvaluationAnalysisReport(
                0, 0, 0, 0, 0, 0.0,
                0, 0.0, 0, 0.0, 0, 0,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                new ScoreMarginStatistics(0, null, null, null, null, null, 0, 0.0),
                new CostDeltaStatistics(0, null, null, 0, 0, 0, 0.0, 0.0, 0.0),
                new RequirementDimensionStatistics(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                List.of(),
                List.of(new RoutingCalibrationRecommendation(
                        "NO_DATA",
                        RoutingAnalysisSeverity.INFO,
                        RoutingAnalysisIssueCategory.DATA_QUALITY,
                        "dataset",
                        "No evaluation records available for analysis.",
                        "Provide a non-empty dataset of RoutingEvaluationRecords."
                )),
                true
        );
    }

    private static class PairAccumulator {
        final String top1;
        final String top2;
        long count = 0;
        double totalMargin = 0.0;
        long marginCount = 0;

        PairAccumulator(String top1, String top2) {
            this.top1 = top1;
            this.top2 = top2;
        }
    }
}
