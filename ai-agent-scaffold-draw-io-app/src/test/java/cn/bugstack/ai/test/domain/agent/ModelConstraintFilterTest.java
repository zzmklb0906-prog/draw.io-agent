package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.catalog.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.constraint.*;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RequirementEvidence;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.RoutingRequirement;
import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultModelConstraintFilter} (Phase 4 Hard Constraint Filter).
 *
 * <p>Validates vision, context window, max output hard constraints, UNKNOWN vision handling,
 * multi-violation accumulation, soft-requirement isolation, and defensive boundary safety.</p>
 */
class ModelConstraintFilterTest {

    private DefaultModelConstraintFilter filter;

    @BeforeEach
    void setUp() {
        this.filter = new DefaultModelConstraintFilter();
    }

    // =========================================================================
    // Case 1: Normal pure text request, all eligible models accepted
    // =========================================================================

    @Test
    void case1_normalPureTextRequest_allEnabledModelsAccepted() {
        RoutingRequirement req = createRequirement(false, 10000L, 2048L, 50, 50);
        List<ModelProfile> candidates = List.of(
                createModel("m1", true, SupportStatus.SUPPORTED, 131072L, 8192L, 80),
                createModel("m2", true, SupportStatus.UNSUPPORTED, 131072L, 8192L, 80)
        );

        ModelFilterResult result = filter.filter(req, candidates);

        assertEquals(2, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
        assertTrue(result.hasAcceptedModels());
    }

    // =========================================================================
    // Case 2 & 3: Vision Hard Constraint (UNSUPPORTED vs SUPPORTED)
    // =========================================================================

    @Test
    void case2_visionRequired_unsupportedModelRejected() {
        RoutingRequirement req = createRequirement(true, 10000L, 2048L, 50, 50);
        List<ModelProfile> candidates = List.of(
                createModel("text-only-model", true, SupportStatus.UNSUPPORTED, 131072L, 8192L, 80)
        );

        ModelFilterResult result = filter.filter(req, candidates);

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        RejectedModel rejected = result.rejected().get(0);
        assertEquals("text-only-model", rejected.model().id());
        assertTrue(rejected.violations().stream().anyMatch(v -> v.reason() == ConstraintReason.VISION_UNSUPPORTED));
    }

    @Test
    void case3_visionRequired_supportedModelAccepted() {
        RoutingRequirement req = createRequirement(true, 10000L, 2048L, 50, 50);
        List<ModelProfile> candidates = List.of(
                createModel("vision-model", true, SupportStatus.SUPPORTED, 131072L, 8192L, 80)
        );

        ModelFilterResult result = filter.filter(req, candidates);

        assertEquals(1, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
    }

    // =========================================================================
    // Case 4: Vision UNKNOWN Semantics (UNKNOWN != UNSUPPORTED)
    // =========================================================================

    @Test
    void case4_visionRequired_unknownVisionAcceptedWithWarning() {
        RoutingRequirement req = createRequirement(true, 10000L, 2048L, 50, 50);
        List<ModelProfile> candidates = List.of(
                createModel("unknown-vision-model", true, SupportStatus.UNKNOWN, 131072L, 8192L, 80)
        );

        ModelFilterResult result = filter.filter(req, candidates);

        // Must NOT be hard-rejected
        assertEquals(1, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
        // Must emit warning
        assertEquals(1, result.warnings().size());
        assertEquals(ConstraintReason.VISION_SUPPORT_UNKNOWN, result.warnings().get(0).reason());
    }

    // =========================================================================
    // Case 5 & 6: Context Window Constraint (Exceeded vs Exact match)
    // =========================================================================

    @Test
    void case5_contextWindowTooSmall_rejected() {
        RoutingRequirement req = createRequirement(false, 200000L, 2048L, 50, 50);
        List<ModelProfile> candidates = List.of(
                createModel("small-context-model", true, SupportStatus.SUPPORTED, 131072L, 8192L, 80)
        );

        ModelFilterResult result = filter.filter(req, candidates);

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).violations().stream()
                .anyMatch(v -> v.reason() == ConstraintReason.CONTEXT_WINDOW_TOO_SMALL));
    }

    @Test
    void case6_contextWindowExactMatch_accepted() {
        RoutingRequirement req = createRequirement(false, 131072L, 2048L, 50, 50);
        List<ModelProfile> candidates = List.of(
                createModel("exact-context-model", true, SupportStatus.SUPPORTED, 131072L, 8192L, 80)
        );

        ModelFilterResult result = filter.filter(req, candidates);

        assertEquals(1, result.accepted().size(), "Exact boundary match must be accepted");
        assertTrue(result.rejected().isEmpty());
    }

    // =========================================================================
    // Case 7 & 8: Max Output Constraint (Exceeded vs Exact match)
    // =========================================================================

    @Test
    void case7_maxOutputTooSmall_rejected() {
        RoutingRequirement req = createRequirement(false, 10000L, 16384L, 50, 50);
        List<ModelProfile> candidates = List.of(
                createModel("small-output-model", true, SupportStatus.SUPPORTED, 131072L, 8192L, 80)
        );

        ModelFilterResult result = filter.filter(req, candidates);

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).violations().stream()
                .anyMatch(v -> v.reason() == ConstraintReason.MAX_OUTPUT_TOO_SMALL));
    }

    @Test
    void case8_maxOutputExactMatch_accepted() {
        RoutingRequirement req = createRequirement(false, 10000L, 8192L, 50, 50);
        List<ModelProfile> candidates = List.of(
                createModel("exact-output-model", true, SupportStatus.SUPPORTED, 131072L, 8192L, 80)
        );

        ModelFilterResult result = filter.filter(req, candidates);

        assertEquals(1, result.accepted().size(), "Exact output match must be accepted");
        assertTrue(result.rejected().isEmpty());
    }

    // =========================================================================
    // Multiple Violations Accumulation
    // =========================================================================

    @Test
    void multipleViolations_accumulatesAllReasons() {
        // Requires: Vision + 200K Context + 16K Output
        RoutingRequirement req = createRequirement(true, 200000L, 16384L, 50, 50);
        // Model has: No Vision + 128K Context + 8K Output
        ModelProfile model = createModel("triple-fail-model", true, SupportStatus.UNSUPPORTED, 131072L, 8192L, 80);

        ModelFilterResult result = filter.filter(req, List.of(model));

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        RejectedModel rejected = result.rejected().get(0);
        assertEquals(3, rejected.violations().size(), "Must accumulate all 3 distinct violation reasons");
        assertTrue(rejected.violations().stream().anyMatch(v -> v.reason() == ConstraintReason.VISION_UNSUPPORTED));
        assertTrue(rejected.violations().stream().anyMatch(v -> v.reason() == ConstraintReason.CONTEXT_WINDOW_TOO_SMALL));
        assertTrue(rejected.violations().stream().anyMatch(v -> v.reason() == ConstraintReason.MAX_OUTPUT_TOO_SMALL));
    }

    // =========================================================================
    // Defensive: Disabled Model & Invalid Metadata
    // =========================================================================

    @Test
    void disabledModel_isRejected() {
        RoutingRequirement req = createRequirement(false, 10000L, 2048L, 50, 50);
        ModelProfile disabledModel = createModel("disabled-model", false, SupportStatus.SUPPORTED, 131072L, 8192L, 80);

        ModelFilterResult result = filter.filter(req, List.of(disabledModel));

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).violations().stream()
                .anyMatch(v -> v.reason() == ConstraintReason.MODEL_DISABLED));
    }

    @Test
    void invalidModelMetadata_doesNotNPE_andRejects() {
        RoutingRequirement req = createRequirement(false, 10000L, 2048L, 50, 50);
        ModelProfile brokenModel = new ModelProfile(
                "broken", "qwen", "broken-model", true,
                null, null, null, null // missing limits and features
        );

        ModelFilterResult result = filter.filter(req, List.of(brokenModel));

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).violations().stream()
                .anyMatch(v -> v.reason() == ConstraintReason.INVALID_MODEL_METADATA));
    }

    @Test
    void invalidRequirement_nullOrNegative_failsSafely() {
        ModelProfile model = createModel("good-model", true, SupportStatus.SUPPORTED, 131072L, 8192L, 80);

        ModelFilterResult nullReqResult = filter.filter(null, List.of(model));
        assertTrue(nullReqResult.accepted().isEmpty());
        assertEquals(1, nullReqResult.rejected().size());
        assertEquals(ConstraintReason.INVALID_REQUIREMENT, nullReqResult.rejected().get(0).violations().get(0).reason());

        RoutingRequirement negativeReq = createRequirement(false, -100L, 2048L, 50, 50);
        ModelFilterResult negResult = filter.filter(negativeReq, List.of(model));
        assertTrue(negResult.accepted().isEmpty());
        assertEquals(ConstraintReason.INVALID_REQUIREMENT, negResult.rejected().get(0).violations().get(0).reason());
    }

    // =========================================================================
    // Soft Capability Isolation: Reasoning / Coding / Tools NEVER Cause Hard Reject
    // =========================================================================

    @Test
    void softCapability_lowReasoningAndCoding_doesNotReject() {
        // High soft capability demands
        RoutingRequirement req = createRequirement(false, 10000L, 2048L, 100, 100);
        // Low capability scores on model, but hard constraints are all satisfied
        ModelProfile model = createModel("weak-reasoning-model", true, SupportStatus.SUPPORTED, 131072L, 8192L, 10);

        ModelFilterResult result = filter.filter(req, List.of(model));

        assertEquals(1, result.accepted().size(),
                "Soft capability mismatch must NEVER cause hard constraint rejection in Phase 4");
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void softCapability_toolCallingScoreDoesNotReject() {
        // High tool-calling soft demand
        RoutingRequirement req = new RoutingRequirement(
                TaskType.DRAWIO_GENERATION,
                80, 80, 80, 80, 100, // toolCallingRequired = 100
                false, 10000L, 4096L, "agent_drawer", RequirementEvidence.empty()
        );
        // Model with UNSUPPORTED tool feature (soft score is not a hard boolean)
        ModelProfile model = new ModelProfile(
                "no-tool-model", "qwen", "no-tool-model", true,
                new ModelCapabilities(80, 80, 80, 80, 10, 80, 80),
                new ModelFeatures(SupportStatus.UNSUPPORTED, SupportStatus.SUPPORTED, SupportStatus.SUPPORTED),
                new ModelLimits(131072L, 8192L),
                new ModelPricing(BigDecimal.ONE, BigDecimal.TEN, "CNY")
        );

        ModelFilterResult result = filter.filter(req, List.of(model));

        // In Phase 4, toolCallingRequired is soft score; must not reject
        assertEquals(1, result.accepted().size());
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private RoutingRequirement createRequirement(boolean vision, long minContext, long expectedOutput, int reasoning, int coding) {
        return new RoutingRequirement(
                TaskType.GENERAL_CHAT,
                reasoning, 50, coding, 50, 50,
                vision, minContext, expectedOutput, "agent_analyst", RequirementEvidence.empty()
        );
    }

    private ModelProfile createModel(String id, boolean enabled, SupportStatus vision, long contextTokens, long maxOutputTokens, int reasoningScore) {
        return new ModelProfile(
                id,
                "qwen",
                id,
                enabled,
                new ModelCapabilities(reasoningScore, 80, 80, 80, 80, 80, 80),
                new ModelFeatures(SupportStatus.SUPPORTED, SupportStatus.SUPPORTED, vision),
                new ModelLimits(contextTokens, maxOutputTokens),
                new ModelPricing(BigDecimal.ONE, BigDecimal.TEN, "CNY")
        );
    }
}
