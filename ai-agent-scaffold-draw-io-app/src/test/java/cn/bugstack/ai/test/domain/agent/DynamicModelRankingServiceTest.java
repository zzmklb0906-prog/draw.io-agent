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
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.CandidateScore;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DefaultModelRanker;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.DynamicModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScoringProperties;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.RankingResult;
import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.WeightedModelScorer;
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
 * Integration unit test for {@link DynamicModelRankingService} with real {@code model-catalog.yml} models.
 */
class DynamicModelRankingServiceTest {

    private ModelConstraintFilteringService filteringService;
    private DynamicModelRankingService rankingService;

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

        WeightedModelScorer scorer = new WeightedModelScorer(new ModelScoringProperties());
        DefaultModelRanker ranker = new DefaultModelRanker(scorer);
        this.rankingService = new DynamicModelRankingService(ranker);
    }

    @Test
    void simpleEditTask_recommendsCostEffectiveFlash() {
        // Low requirement: SIMPLE_EDIT
        RoutingRequirement req = new RoutingRequirement(
                TaskType.SIMPLE_EDIT,
                30, 70, 10, 30, 10,
                false, 10000L, 512L, "agent_analyst", RequirementEvidence.empty()
        );

        ModelFilterResult filterResult = filteringService.filter(req);
        RankingResult rankingResult = rankingService.rank(req, filterResult);

        assertFalse(rankingResult.isEmpty());
        CandidateScore top = rankingResult.topCandidate().orElseThrow();
        // For simple tasks, Flash provides 100% capability fit with 1/15 cost of Max -> ranks top
        assertEquals("qwen3.7-flash", top.model().id(),
                "For simple lightweight editing tasks, cost-effective Flash must be recommended");
    }

    @Test
    void complexReasoningTask_recommendsStrongestMax() {
        // High requirement: ANALYZE / DIAGNOSE (reasoning 95)
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DIAGNOSE,
                95, 95, 80, 80, 80,
                false, 10000L, 4096L, "agent_analyst", RequirementEvidence.empty()
        );

        ModelFilterResult filterResult = filteringService.filter(req);
        RankingResult rankingResult = rankingService.rank(req, filterResult);

        assertFalse(rankingResult.isEmpty());
        CandidateScore top = rankingResult.topCandidate().orElseThrow();
        assertEquals("qwen3.8-max", top.model().id(),
                "For complex high-reasoning tasks, qwen3.8-max must be recommended");
    }

    @Test
    void emptyAccepted_returnsEmptyRankingSafely() {
        // Requirement that exceeds all models (2M tokens context)
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DRAWIO_GENERATION,
                65, 95, 55, 98, 88,
                false, 2000000L, 16384L, "agent_drawer", RequirementEvidence.empty()
        );

        ModelFilterResult filterResult = filteringService.filter(req);
        assertTrue(filterResult.accepted().isEmpty());

        RankingResult rankingResult = rankingService.rank(req, filterResult);
        assertTrue(rankingResult.isEmpty(), "When accepted is empty, ranking must safely return empty");
        assertTrue(rankingResult.topCandidate().isEmpty());
    }
}
