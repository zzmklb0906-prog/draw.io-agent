package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.CandidateModelSelector;
import cn.bugstack.ai.domain.agent.service.llm.routing.candidate.DefaultModelCapabilityFilter;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.DefaultModelRankingEngine;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.ModelRankingProperties;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.ModelRankingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.ranking.RankedModel;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.LatencySensitivity;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 — Runtime Feedback & Health-aware Routing Tests (Case 1 to Case 7)
 */
@DisplayName("Phase 7 Runtime Feedback & Health-aware Routing Tests")
public class RuntimeFeedbackTest {

    private RuntimeFeedbackProperties properties;
    private RuntimeHealthEvaluator healthEvaluator;
    private RuntimeMetricsAggregator aggregator;
    private InMemoryModelRuntimeProfileStore store;
    private RuntimeFeedbackCollector collector;

    @BeforeEach
    void setUp() {
        properties = new RuntimeFeedbackProperties();
        properties.setMinSamplesForHealth(5);
        properties.setHealthySuccessThreshold(0.95);
        properties.setDegradedSuccessThreshold(0.80);
        properties.setMaxHealthyTimeoutRate(0.05);

        healthEvaluator = new RuntimeHealthEvaluator(properties);
        aggregator = new RuntimeMetricsAggregator(healthEvaluator);
        store = new InMemoryModelRuntimeProfileStore();
        collector = new RuntimeFeedbackCollector(aggregator, store, properties);
    }

    // =========================================================================
    // Case 1: Successful Execution Increases Success Rate
    // =========================================================================
    @Test
    @DisplayName("Case 1: 成功请求反馈 - 验证 success=true 正确累计并计算成功率")
    void case1_successfulExecutionFeedback() {
        for (int i = 0; i < 10; i++) {
            collector.collect(ModelExecutionResult.success("req-" + i, "qwen-plus", "qwen3.7-plus", 1200L, 500, 200));
        }

        ModelRuntimeProfile profile = store.find("qwen-plus").orElseThrow();
        assertEquals(10L, profile.sampleCount());
        assertEquals(1.0, profile.successRate());
        assertEquals(0.0, profile.timeoutRate());
        assertEquals(1200.0, profile.averageLatencyMs());
        assertEquals(RuntimeHealth.HEALTHY, profile.health());
    }

    // =========================================================================
    // Case 2: Failed Execution with TIMEOUT Increases Timeout Rate
    // =========================================================================
    @Test
    @DisplayName("Case 2: 超时失败反馈 - 验证 failureType=TIMEOUT 正确累计并增加 timeoutRate")
    void case2_timeoutFailureFeedback() {
        for (int i = 0; i < 8; i++) {
            collector.collect(ModelExecutionResult.success("req-" + i, "qwen-max", "qwen3.7-max", 1500L, 500, 200));
        }
        for (int i = 8; i < 10; i++) {
            collector.collect(ModelExecutionResult.failure("req-" + i, "qwen-max", "qwen3.7-max", 10000L, FailureType.TIMEOUT, "Read timeout"));
        }

        ModelRuntimeProfile profile = store.find("qwen-max").orElseThrow();
        assertEquals(10L, profile.sampleCount());
        assertEquals(0.80, profile.successRate(), 0.01);
        assertEquals(0.20, profile.timeoutRate(), 0.01);
        assertEquals(RuntimeHealth.DEGRADED, profile.health());
    }

    // =========================================================================
    // Case 3: Sample Count Correct Accumulation
    // =========================================================================
    @Test
    @DisplayName("Case 3: 样本数统计 - 验证多次调用后样本计数严格准确")
    void case3_sampleCountAccumulation() {
        for (int i = 0; i < 25; i++) {
            collector.collect(ModelExecutionResult.success("req-" + i, "qwen-flash", "qwen3.7-flash", 400L, 200, 100));
        }

        ModelRuntimeProfile profile = store.find("qwen-flash").orElseThrow();
        assertEquals(25L, profile.sampleCount());
    }

    // =========================================================================
    // Case 4: Health Evaluation State Transitions
    // =========================================================================
    @Test
    @DisplayName("Case 4: 健康状态评估 - 验证 UNKNOWN -> HEALTHY -> DEGRADED -> UNAVAILABLE 转换")
    void case4_healthEvaluationTransitions() {
        // Insufficient samples (< 5) -> UNKNOWN
        assertEquals(RuntimeHealth.UNKNOWN, healthEvaluator.evaluate(1.0, 0.0, 3L));

        // >= 5 samples & success >= 0.95 -> HEALTHY
        assertEquals(RuntimeHealth.HEALTHY, healthEvaluator.evaluate(0.98, 0.01, 100L));

        // 0.80 <= success < 0.95 or timeout > 0.05 -> DEGRADED
        assertEquals(RuntimeHealth.DEGRADED, healthEvaluator.evaluate(0.90, 0.02, 100L));
        assertEquals(RuntimeHealth.DEGRADED, healthEvaluator.evaluate(0.98, 0.10, 100L));

        // success < 0.80 -> UNAVAILABLE
        assertEquals(RuntimeHealth.UNAVAILABLE, healthEvaluator.evaluate(0.70, 0.0, 100L));
    }

    // =========================================================================
    // Case 5: Runtime Profile Snapshot Verification
    // =========================================================================
    @Test
    @DisplayName("Case 5: Runtime Profile 快照输出 - 验证不可变 Snapshot 的一致性")
    void case5_runtimeProfileSnapshot() {
        List<RuntimeFeedback> feedbacks = List.of(
                new RuntimeFeedback("r1", "model-test", true, 1000L, FailureType.NONE, null, 100, 50, Instant.now()),
                new RuntimeFeedback("r2", "model-test", true, 2000L, FailureType.NONE, null, 100, 50, Instant.now()),
                new RuntimeFeedback("r3", "model-test", false, 3000L, FailureType.TIMEOUT, "Timeout", 100, 50, Instant.now()),
                new RuntimeFeedback("r4", "model-test", true, 1000L, FailureType.NONE, null, 100, 50, Instant.now()),
                new RuntimeFeedback("r5", "model-test", true, 1000L, FailureType.NONE, null, 100, 50, Instant.now())
        );

        ModelRuntimeProfile snapshot = aggregator.aggregateBatch("model-test", feedbacks);

        assertEquals(5L, snapshot.sampleCount());
        assertEquals(0.80, snapshot.successRate(), 0.01);
        assertEquals(0.20, snapshot.timeoutRate(), 0.01);
        assertEquals(1600.0, snapshot.averageLatencyMs(), 0.1);
        assertEquals(RuntimeHealth.DEGRADED, snapshot.health());
    }

    // =========================================================================
    // Case 6: Ranking Reads Updated RuntimeProfile & Influences Reliability
    // =========================================================================
    @Test
    @DisplayName("Case 6: Ranking 读取最新 RuntimeProfile - 验证实时聚合的 Profile 直接影响 Reliability 打分")
    void case6_rankingReadsUpdatedProfile() {
        ModelProfile modelGood = createModel("model-good", "Model Good", 80, 0.001, 0.002);
        ModelProfile modelFlaky = createModel("model-flaky", "Model Flaky", 80, 0.001, 0.002);

        ModelCatalogService catalog = new ModelCatalogService(new ModelCatalogProperties());
        catalog.registerModel(modelGood);
        catalog.registerModel(modelFlaky);

        // Record high success for modelGood (10 successes -> HEALTHY)
        for (int i = 0; i < 10; i++) {
            collector.collect(ModelExecutionResult.success("g-" + i, "model-good", "Model Good", 800L, 500, 200));
        }

        // Record degraded stats for modelFlaky (9 successes, 1 timeout -> DEGRADED)
        for (int i = 0; i < 9; i++) {
            collector.collect(ModelExecutionResult.success("f-" + i, "model-flaky", "Model Flaky", 800L, 500, 200));
        }
        collector.collect(ModelExecutionResult.failure("f-err", "model-flaky", "Model Flaky", 5000L, FailureType.TIMEOUT, "Timeout"));

        CandidateModelSelector selector = new CandidateModelSelector(catalog, new DefaultModelCapabilityFilter());
        DefaultModelRankingEngine rankingEngine = new DefaultModelRankingEngine(new ModelRankingProperties());
        ModelRankingService rankingService = new ModelRankingService(selector, rankingEngine, store);

        RoutingRequirement req = new RoutingRequirement(
                TaskType.GENERAL_CHAT, 70, 70, 0, 0, 0, false, 4096L, 1024L, "agent",
                RequirementEvidence.empty(), LatencySensitivity.NORMAL
        );

        List<RankedModel> ranked = rankingService.rank(req);

        assertEquals(2, ranked.size());
        assertEquals("model-good", ranked.get(0).modelProfile().id());
        assertEquals("model-flaky", ranked.get(1).modelProfile().id());
        assertTrue(ranked.get(0).breakdown().reliabilityFit() > ranked.get(1).breakdown().reliabilityFit());
    }

    // =========================================================================
    // Case 7: Feedback Telemetry Does NOT Modify Static Ranking Properties
    // =========================================================================
    @Test
    @DisplayName("Case 7: 禁止在线修改权重 - 验证反馈收集与聚合过程不篡改 Ranking 权重配置")
    void case7_feedbackDoesNotMutateRankingProperties() {
        ModelRankingProperties rankProps = new ModelRankingProperties();
        double originalCapWeight = rankProps.getCapabilityWeight();
        double originalRelWeight = rankProps.getReliabilityWeight();

        for (int i = 0; i < 50; i++) {
            collector.collect(ModelExecutionResult.success("r-" + i, "qwen-plus", "qwen3.7-plus", 1000L, 500, 200));
        }

        // Assert static configuration remains immutable
        assertEquals(originalCapWeight, rankProps.getCapabilityWeight());
        assertEquals(originalRelWeight, rankProps.getReliabilityWeight());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ModelProfile createModel(String id, String name, int reasoning, double inPrice, double outPrice) {
        return new ModelProfile(
                id, "generic", name, true,
                new ModelCapabilities(reasoning, 80, 80, 80, 80, 0, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, SupportStatus.UNSUPPORTED),
                new ModelLimits(65536L, 4096L),
                new ModelPricing(BigDecimal.valueOf(inPrice), BigDecimal.valueOf(outPrice), "USD")
        );
    }
}
