package cn.bugstack.ai.domain.agent.service.llm.routing.constraint;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.catalog.SupportStatus;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Default deterministic implementation of {@link ModelConstraintFilter}.
 *
 * <p><strong>Evaluation Rules:</strong>
 * <ol>
 *   <li><strong>Enabled & Metadata Safety:</strong> Disabled models and models with missing limits/features are rejected.</li>
 *   <li><strong>Vision Hard Constraint:</strong> If {@code visionRequired=true}, models with {@code vision=UNSUPPORTED} are rejected. Models with {@code vision=UNKNOWN} produce a warning but are NOT rejected.</li>
 *   <li><strong>Context Window Constraint:</strong> If {@code minContextWindowTokens > contextWindowTokens}, rejected.</li>
 *   <li><strong>Max Output Constraint:</strong> If {@code expectedOutputTokens > maxOutputTokens}, rejected.</li>
 *   <li><strong>Soft Requirements Isolation:</strong> Reasoning, coding, tool-calling, and structured-output scores are NEVER evaluated for rejection.</li>
 * </ol>
 * </p>
 */
@Component
public class DefaultModelConstraintFilter implements ModelConstraintFilter {

    @Override
    public ModelFilterResult filter(RoutingRequirement requirement, List<ModelProfile> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return ModelFilterResult.empty();
        }

        // Defensive check on requirement validity
        if (requirement == null || requirement.minContextWindowTokens() < 0 || requirement.expectedOutputTokens() < 0) {
            List<RejectedModel> rejectedAll = new ArrayList<>();
            for (ModelProfile candidate : candidates) {
                if (candidate != null) {
                    rejectedAll.add(new RejectedModel(candidate, List.of(
                            new ConstraintViolation(ConstraintReason.INVALID_REQUIREMENT, "requirement", "Requirement is null or contains negative token limits")
                    )));
                }
            }
            return new ModelFilterResult(List.of(), rejectedAll, List.of());
        }

        List<ModelProfile> accepted = new ArrayList<>();
        List<RejectedModel> rejected = new ArrayList<>();
        List<ModelConstraintWarning> warnings = new ArrayList<>();

        for (ModelProfile model : candidates) {
            List<ConstraintViolation> violations = new ArrayList<>();

            // 1. Null model profile check
            // TODO: In future strict audit mode, record null candidates as INVALID_MODEL_METADATA rather than silently skipping.
            if (model == null) {
                continue;
            }

            // 2. Disabled check
            if (!model.enabled()) {
                violations.add(new ConstraintViolation(
                        ConstraintReason.MODEL_DISABLED,
                        "enabled",
                        "Model is disabled in catalog"
                ));
            }

            // 3. Metadata presence check
            if (model.limits() == null || model.features() == null) {
                violations.add(new ConstraintViolation(
                        ConstraintReason.INVALID_MODEL_METADATA,
                        "limits/features",
                        "Model profile is missing limits or features metadata"
                ));
            } else {
                // 4. Vision Hard Constraint: only explicit SUPPORTED passes when visionRequired=true
                if (requirement.visionRequired()) {
                    SupportStatus visionStatus = model.features().vision();
                    if (visionStatus == SupportStatus.UNSUPPORTED) {
                        violations.add(new ConstraintViolation(
                                ConstraintReason.VISION_UNSUPPORTED,
                                "features.vision",
                                "Task requires multimodal vision, but model does not support vision"
                        ));
                    } else if (visionStatus == SupportStatus.UNKNOWN) {
                        violations.add(new ConstraintViolation(
                                ConstraintReason.VISION_SUPPORT_UNKNOWN,
                                "features.vision",
                                "Task requires multimodal vision, but model vision support is UNKNOWN"
                        ));
                    }
                }

                // 5. Context Window Capacity Constraint
                long requiredContext = requirement.minContextWindowTokens();
                long availableContext = model.limits().contextWindowTokens();
                if (requiredContext > availableContext) {
                    violations.add(new ConstraintViolation(
                            ConstraintReason.CONTEXT_WINDOW_TOO_SMALL,
                            "limits.contextWindowTokens",
                            String.format("Required context capacity (%d tokens) exceeds model limit (%d tokens)", requiredContext, availableContext)
                    ));
                }

                // 6. Max Output Budget Constraint
                long requiredOutput = requirement.expectedOutputTokens();
                long availableOutput = model.limits().maxOutputTokens();
                if (requiredOutput > availableOutput) {
                    violations.add(new ConstraintViolation(
                            ConstraintReason.MAX_OUTPUT_TOO_SMALL,
                            "limits.maxOutputTokens",
                            String.format("Expected output tokens (%d tokens) exceeds model max output limit (%d tokens)", requiredOutput, availableOutput)
                    ));
                }
            }

            // Accumulate result
            if (violations.isEmpty()) {
                accepted.add(model);
            } else {
                rejected.add(new RejectedModel(model, List.copyOf(violations)));
            }
        }

        return new ModelFilterResult(accepted, rejected, warnings);
    }
}
