package cn.bugstack.ai.domain.agent.service.llm.routing.constraint;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;

import java.util.List;

/**
 * Result of Hard Constraint Filtering.
 *
 * <p>Contains immutable lists of accepted models, rejected models with violations,
 * and warnings. Does NOT rank models or select a best model.</p>
 */
public record ModelFilterResult(
        List<ModelProfile> accepted,
        List<RejectedModel> rejected,
        List<ModelConstraintWarning> warnings
) {
    public ModelFilterResult {
        accepted = accepted != null ? List.copyOf(accepted) : List.of();
        rejected = rejected != null ? List.copyOf(rejected) : List.of();
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    public boolean hasAcceptedModels() {
        return !accepted.isEmpty();
    }

    public static ModelFilterResult empty() {
        return new ModelFilterResult(List.of(), List.of(), List.of());
    }
}
