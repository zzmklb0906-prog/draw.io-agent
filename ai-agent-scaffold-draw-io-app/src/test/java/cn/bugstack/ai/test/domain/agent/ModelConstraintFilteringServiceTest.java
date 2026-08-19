package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogProperties;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelCatalogService;
import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.DefaultModelConstraintFilter;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelConstraintFilteringService;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.ModelFilterResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration unit test for {@link ModelConstraintFilteringService} with real {@code model-catalog.yml}.
 */
class ModelConstraintFilteringServiceTest {

    private ModelConstraintFilteringService filteringService;

    @BeforeEach
    void setUp() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        List<PropertySource<?>> catalogSources = loader.load("model-catalog.yml", new ClassPathResource("model-catalog.yml"));
        for (PropertySource<?> source : catalogSources) {
            propertySources.addLast(source);
        }

        ConfigurationPropertySources.attach(environment);
        Binder binder = Binder.get(environment);

        ModelCatalogProperties catalogProperties = binder.bind("ai.agent.model-catalog", ModelCatalogProperties.class)
                .orElseGet(ModelCatalogProperties::new);
        ModelCatalogService catalogService = new ModelCatalogService(catalogProperties);
        DefaultModelConstraintFilter constraintFilter = new DefaultModelConstraintFilter();

        this.filteringService = new ModelConstraintFilteringService(catalogService, constraintFilter);
    }

    @Test
    void normalRequirement_acceptsAllThreeActiveQwenModels() {
        // Standard drawio generation task requirement
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DRAWIO_GENERATION,
                65, 95, 55, 98, 88,
                false, 10000L, 16384L, "agent_drawer", RequirementEvidence.empty()
        );

        ModelFilterResult result = filteringService.filter(req);

        // The 3 enabled Qwen models (flash, plus, max) all have 1M context and 128K maxOutput
        assertEquals(3, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
        List<String> acceptedIds = result.accepted().stream().map(ModelProfile::id).toList();
        assertTrue(acceptedIds.contains("qwen3.7-flash"));
        assertTrue(acceptedIds.contains("qwen3.7-plus"));
        assertTrue(acceptedIds.contains("qwen3.8-max"));
    }

    @Test
    void oversizedContextRequirement_rejectsAllWhenCapacityExceeded() {
        // Requirement requiring 2M tokens context (exceeding all 1M models)
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DRAWIO_GENERATION,
                65, 95, 55, 98, 88,
                false, 2000000L, 16384L, "agent_drawer", RequirementEvidence.empty()
        );

        ModelFilterResult result = filteringService.filter(req);

        assertTrue(result.accepted().isEmpty());
        assertEquals(3, result.rejected().size());
    }
}
