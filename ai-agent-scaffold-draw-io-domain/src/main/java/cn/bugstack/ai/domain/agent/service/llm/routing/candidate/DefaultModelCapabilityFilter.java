package cn.bugstack.ai.domain.agent.service.llm.routing.candidate;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of {@link ModelCapabilityFilter}.
 *
 * <p><strong>Filtering Rules:</strong>
 * <ol>
 *   <li><strong>Enabled Check:</strong> Only models with {@code enabled=true} are considered.</li>
 *   <li><strong>Tool Calling:</strong> If {@code requirement.needToolCalling()=true}, model must support tool calling.</li>
 *   <li><strong>Vision:</strong> If {@code requirement.needVision()=true}, model must support vision.</li>
 *   <li><strong>Structured Output:</strong> If {@code requirement.needStructuredOutput()=true}, model must support structured output.</li>
 *   <li><strong>Context Window:</strong> If {@code requirement.minContextWindowTokens() > 0}, {@code model.contextWindow()} must be &gt;= requirement.</li>
 * </ol>
 * </p>
 */
@Component
public class DefaultModelCapabilityFilter implements ModelCapabilityFilter {

    @Override
    public List<ModelProfile> filter(RoutingRequirement requirement, List<ModelProfile> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }

        List<ModelProfile> compliant = new ArrayList<>();
        for (ModelProfile model : models) {
            if (model == null) {
                continue;
            }

            // Rule 0: Model must be enabled
            if (!model.enabled()) {
                continue;
            }

            if (requirement != null) {
                // Rule 1: Tool Calling
                if (requirement.needToolCalling() && !model.supportsToolCalling()) {
                    continue;
                }

                // Rule 2: Vision
                if (requirement.needVision() && !model.supportsVision()) {
                    continue;
                }

                // Rule 3: Structured Output
                if (requirement.needStructuredOutput() && !model.supportsStructuredOutput()) {
                    continue;
                }

                // Rule 4: Context Window Capacity
                if (requirement.minContextWindowTokens() > 0 && model.contextWindow() < requirement.minContextWindowTokens()) {
                    continue;
                }
            }

            compliant.add(model);
        }

        return List.copyOf(compliant);
    }
}
