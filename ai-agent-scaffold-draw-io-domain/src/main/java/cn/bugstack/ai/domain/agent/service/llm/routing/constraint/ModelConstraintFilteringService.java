package cn.bugstack.ai.domain.agent.service.llm.routing.constraint;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for orchestrating hard constraint filtering across enabled models in Catalog.
 */
@Service
public class ModelConstraintFilteringService {

    private final ModelCatalogService catalogService;
    private final ModelConstraintFilter constraintFilter;

    public ModelConstraintFilteringService(ModelCatalogService catalogService,
                                           ModelConstraintFilter constraintFilter) {
        this.catalogService = catalogService;
        this.constraintFilter = constraintFilter;
    }

    /**
     * Filters all currently enabled models from {@link ModelCatalogService} against the requirement.
     *
     * <p><strong>TODO (Phase 5):</strong> When {@code result.accepted()} is empty (No Eligible Candidate),
     * treat it as a first-class state and design explicit fallback paths (e.g. legacy route or explicit failure policy).
     * Never assume {@code accepted} is non-empty.</p>
     *
     * @param requirement the request requirements
     * @return filter result containing accepted and rejected models
     */
    public ModelFilterResult filter(RoutingRequirement requirement) {
        List<ModelProfile> enabledModels = catalogService.getEnabledModels();
        return constraintFilter.filter(requirement, enabledModels);
    }

    /**
     * Filters an explicit list of candidate model profiles against the requirement.
     *
     * @param requirement the request requirements
     * @param candidates explicit candidate list
     * @return filter result
     */
    public ModelFilterResult filter(RoutingRequirement requirement, List<ModelProfile> candidates) {
        return constraintFilter.filter(requirement, candidates);
    }
}
