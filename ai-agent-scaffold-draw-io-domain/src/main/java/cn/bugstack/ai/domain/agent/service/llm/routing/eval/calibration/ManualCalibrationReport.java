package cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration;

import java.time.Instant;
import java.util.List;

/**
 * Immutable aggregated report generated from offline benchmark results and human review observations.
 */
public record ManualCalibrationReport(
        String reportId,
        String datasetId,
        String datasetVersion,
        Instant timestamp,
        List<CalibrationProposal> proposals,
        List<String> reliabilityObservations,
        List<String> insufficientEvidenceAreas,
        List<String> humanReviewNotes
) {
    public ManualCalibrationReport {
        proposals = proposals != null ? List.copyOf(proposals) : List.of();
        reliabilityObservations = reliabilityObservations != null ? List.copyOf(reliabilityObservations) : List.of();
        insufficientEvidenceAreas = insufficientEvidenceAreas != null ? List.copyOf(insufficientEvidenceAreas) : List.of();
        humanReviewNotes = humanReviewNotes != null ? List.copyOf(humanReviewNotes) : List.of();
    }
}
