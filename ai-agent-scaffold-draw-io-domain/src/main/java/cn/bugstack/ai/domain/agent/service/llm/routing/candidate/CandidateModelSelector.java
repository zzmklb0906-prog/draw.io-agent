package cn.bugstack.ai.domain.agent.service.llm.routing.candidate;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Candidate Model Selector.
 *
 * <p>Coordinates between {@link ModelCatalogService} and {@link ModelCapabilityFilter}
 * to generate an eligible candidate model pool based on task {@link RoutingRequirement}.</p>
 */
@Component
public class CandidateModelSelector {

    private final ModelCatalogService catalogService;
    private final ModelCapabilityFilter capabilityFilter;

    public CandidateModelSelector(ModelCatalogService catalogService,
                                  ModelCapabilityFilter capabilityFilter) {
        this.catalogService = catalogService;
        this.capabilityFilter = capabilityFilter;
    }

    /**
     * Selects candidate models from catalog matching the given requirement.
     *
     * @param requirement the task requirements
     * @return candidate model pool (never null)
     */
    public List<ModelProfile> select(RoutingRequirement requirement) {
        if (catalogService == null || capabilityFilter == null) {
            return List.of();
        }
        List<ModelProfile> available = catalogService.getAvailableModels();
        return capabilityFilter.filter(requirement, available);
    }
}
