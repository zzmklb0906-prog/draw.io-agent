package cn.bugstack.ai.domain.agent.service.llm.routing.eval.calibration;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;

import java.util.List;
import java.util.Set;

/**
 * Advisory proposal for manual routing parameter calibration.
 *
 * <p><strong>Safety Guarantee:</strong>
 * Proposals are strictly advisory and NEVER automatically applied to configuration.</p>
 */
public record CalibrationProposal(
        String proposalId,
        CalibrationCategory category,
        Set<TaskType> affectedTaskTypes,
        List<String> evidenceCases,
        String observedBehavior,
        String currentValue,
        String suggestedCandidateValue,
        String expectedEffect,
        String risk,
        String confidence, // LOW, MEDIUM, HIGH
        boolean needsHumanApproval
) {
    public CalibrationProposal {
        affectedTaskTypes = affectedTaskTypes != null ? Set.copyOf(affectedTaskTypes) : Set.of();
        evidenceCases = evidenceCases != null ? List.copyOf(evidenceCases) : List.of();
    }
}
