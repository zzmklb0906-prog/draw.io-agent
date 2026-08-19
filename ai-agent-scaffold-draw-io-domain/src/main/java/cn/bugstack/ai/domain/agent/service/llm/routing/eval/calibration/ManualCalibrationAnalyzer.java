package cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Pure analytical service that examines a {@link BenchmarkReport} and optional human review notes
 * to produce actionable, evidence-backed {@link ManualCalibrationReport}.
 *
 * <p><strong>Safety Constraints:</strong></p>
 * <ul>
 *   <li>Completely read-only / analytical: NEVER mutates {@code ModelScoringProperties}, {@code model-catalog.yml}, or router state.</li>
 *   <li>Global parameter proposals require >= 2 independent evidence cases. Single-case signals remain observations only.</li>
 *   <li>Provider execution failures are categorized as reliability observations, never capability downgrades.</li>
 * </ul>
 */
@Slf4j
@Service
public class ManualCalibrationAnalyzer {

    /**
     * Analyzes the benchmark report and generates a calibration report.
     *
     * @param report           The completed benchmark report to analyze.
     * @param humanReviewNotes Optional human review notes / observations.
     * @return Deterministic {@link ManualCalibrationReport}.
     */
    public ManualCalibrationReport analyze(BenchmarkReport report, List<String> humanReviewNotes) {
        if (report == null) {
            return new ManualCalibrationReport(
                    "calib-empty",
                    "none",
                    "none",
                    Instant.now(),
                    List.of(),
                    List.of("No benchmark report available to analyze."),
                    List.of("No benchmark report available."),
                    humanReviewNotes
            );
        }

        List<CalibrationProposal> proposals = new ArrayList<>();
        List<String> reliabilityObservations = new ArrayList<>();
        List<String> insufficientEvidenceAreas = new ArrayList<>();

        if (report.caseEvaluations() == null || report.caseEvaluations().isEmpty()) {
            insufficientEvidenceAreas.add("Dataset contains no executed case evaluations.");
        }


        // 1. Reliability Observations (Provider Failures isolated from Quality)
        if (report.perModelQuality() != null) {
            for (Map.Entry<String, ModelOverallQuality> entry : report.perModelQuality().entrySet()) {
                String model = entry.getKey();
                ModelOverallQuality q = entry.getValue();
                if (q.successRate() < 1.0) {
                    reliabilityObservations.add(String.format(
                            "PROVIDER_RELIABILITY_WARNING: Model [%s] exhibited failure rate (%.1f%%, %d/%d success). Do NOT calibrate capability scores downward for provider timeouts/outages.",
                            model, (1.0 - q.successRate()) * 100, q.successCount(), q.executions()
                    ));
                }
            }
        }

        // 2. Gather Case-level Evidence
        List<String> overRoutingCases = new ArrayList<>();
        List<String> underRoutingCases = new ArrayList<>();
        Set<TaskType> overRoutingTaskTypes = new LinkedHashSet<>();
        Set<TaskType> underRoutingTaskTypes = new LinkedHashSet<>();
        Map<TaskType, Integer> taskTypeCaseCount = new EnumMap<>(TaskType.class);

        for (RoutingQualityEvaluation ev : report.caseEvaluations()) {
            // Count per task type
            if (ev.dynamicClassification() == RoutingQualityClassification.POTENTIAL_OVER_ROUTING) {
                overRoutingCases.add(ev.caseId());
            } else if (ev.dynamicClassification() == RoutingQualityClassification.UNDER_ROUTING) {
                underRoutingCases.add(ev.caseId());
            }
        }

        // Count task type coverage from matrix
        if (report.taskTypeModelMatrix() != null) {
            for (TaskType tt : report.taskTypeModelMatrix().keySet()) {
                taskTypeCaseCount.put(tt, taskTypeCaseCount.getOrDefault(tt, 0) + 1);
            }
        }

        // 3. Minimum Evidence Rule Check for Over-Routing
        if (overRoutingCases.size() >= 2) {
            proposals.add(new CalibrationProposal(
                    "PROP-COST-WEIGHT-001",
                    CalibrationCategory.SCORING_WEIGHT,
                    overRoutingTaskTypes,
                    overRoutingCases,
                    String.format("Dynamic router exhibits POTENTIAL_OVER_ROUTING in %d cases (%s) selecting more expensive models when cheaper models achieved quality-equivalent outputs.",
                            overRoutingCases.size(), String.join(", ", overRoutingCases)),
                    "costWeight: 0.05",
                    "costWeight: 0.08 ~ 0.10",
                    "Increases cost-efficiency penalty for high-end models on simple/equivalent tasks without lowering capability floors.",
                    "Risk of selecting cheaper models if capability requirements are understated.",
                    "MEDIUM",
                    true
            ));
        } else if (!overRoutingCases.isEmpty()) {
            insufficientEvidenceAreas.add(String.format(
                    "Single over-routing case observed [%s]. Per Minimum Evidence Rule (>=2 cases), no global scoring weight proposal generated.",
                    overRoutingCases.get(0)
            ));
        }

        // 4. Minimum Evidence Rule Check for Under-Routing (Quality Risk)
        if (underRoutingCases.size() >= 2) {
            proposals.add(new CalibrationProposal(
                    "PROP-UNDER-ROUTING-001",
                    CalibrationCategory.REQUIREMENT,
                    underRoutingTaskTypes,
                    underRoutingCases,
                    String.format("Dynamic router selected weaker models causing quality deficits in %d cases (%s).",
                            underRoutingCases.size(), String.join(", ", underRoutingCases)),
                    "Current baseline requirement thresholds",
                    "Raise minimum capability requirement for affected task types",
                    "Prevents router from selecting low-capability models for complex reasoning/drawing tasks.",
                    "Slightly increases routing cost by shifting toward higher tier models.",
                    "MEDIUM",
                    true
            ));
        } else if (!underRoutingCases.isEmpty()) {
            insufficientEvidenceAreas.add(String.format(
                    "Single under-routing case observed [%s]. Kept as observation, awaiting broader evaluation coverage.",
                    underRoutingCases.get(0)
            ));
        }

        // 5. Coverage Gaps
        for (TaskType tt : TaskType.values()) {
            if (tt != TaskType.UNKNOWN && !taskTypeCaseCount.containsKey(tt)) {
                insufficientEvidenceAreas.add(String.format("TaskType [%s] has 0 benchmark cases in dataset [%s].", tt, report.datasetId()));
            }
        }

        String reportId = "calib-" + report.datasetId() + "-" + System.currentTimeMillis();
        return new ManualCalibrationReport(
                reportId,
                report.datasetId(),
                report.datasetVersion(),
                Instant.now(),
                proposals,
                reliabilityObservations,
                insufficientEvidenceAreas,
                humanReviewNotes
        );
    }
}
