package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic rule-based requirement analyzer.
 *
 * <p>Constructs capability demands via:
 * <ol>
 *   <li>Task detection on {@code latestUserText} (yielding baseline requirements).</li>
 *   <li>Multimodal Part scanning (detecting true {@code visionRequired}).</li>
 *   <li>Context capacity budgeting ({@code minContextWindowTokens} derived from estimated context + expected output).</li>
 *   <li>Agent-specific specialization bias (via {@link AgentRequirementPolicy}).</li>
 * </ol>
 *
 * <p><strong>Architectural Isolation:</strong>
 * This analyzer operates purely on demand-side signals and has ZERO dependency on
 * {@code ModelCatalogService}.</p>
 */
@Slf4j
@Component
public class RuleBasedRoutingRequirementAnalyzer implements RoutingRequirementAnalyzer {

    private final TaskTypeDetector taskTypeDetector;
    private final List<AgentRequirementPolicy> agentPolicies;

    public RuleBasedRoutingRequirementAnalyzer(TaskTypeDetector taskTypeDetector,
                                               List<AgentRequirementPolicy> agentPolicies) {
        this.taskTypeDetector = taskTypeDetector;
        this.agentPolicies = agentPolicies != null ? agentPolicies : List.of();
    }

    @Override
    public RoutingRequirement analyze(RoutingContext context) {
        if (context == null) {
            return defaultRequirement("unknown");
        }

        String userText = context.latestUserText() != null ? context.latestUserText() : "";
        TaskTypeDetector.DetectionResult detection = taskTypeDetector.detect(userText);
        TaskType taskType = detection.taskType();

        boolean visionRequired = detectMultimodalVision(context.request());
        BaseDemands baseDemands = resolveBaseDemands(taskType);

        long contextTokens = context.estimatedContextTokens();
        long expectedOutput = baseDemands.expectedOutputTokens();
        // 20% safety margin on context + 1024 baseline margin
        long minContextTokens = contextTokens + expectedOutput + (long) (contextTokens * 0.20) + 1024L;

        List<String> adjustments = new ArrayList<>();
        adjustments.add(String.format("taskType: %s baseline", taskType));

        RequirementEvidence evidence = new RequirementEvidence(
                detection.signals(),
                detection.matchedPatterns(),
                adjustments,
                contextTokens,
                expectedOutput
        );

        RoutingRequirement base = new RoutingRequirement(
                taskType,
                clamp(baseDemands.reasoning()),
                clamp(baseDemands.instructionFollowing()),
                clamp(baseDemands.coding()),
                clamp(baseDemands.structuredOutput()),
                clamp(baseDemands.toolCalling()),
                visionRequired,
                minContextTokens,
                expectedOutput,
                context.agentName(),
                evidence
        );

        // Apply matching Agent requirement policy
        String agentName = context.agentName();
        for (AgentRequirementPolicy policy : agentPolicies) {
            if (policy.supports(agentName)) {
                return policy.adjust(context, base);
            }
        }

        return base;
    }

    // -------------------------------------------------------------------------
    // Multimodal Vision Detection
    // -------------------------------------------------------------------------

    private boolean detectMultimodalVision(LlmRequest request) {
        if (request == null || request.contents() == null) {
            return false;
        }
        for (Content content : request.contents()) {
            if (content == null) continue;
            List<Part> parts = content.parts().orElse(List.of());
            for (Part part : parts) {
                if (part == null) continue;
                // Check inlineData blob mimeType
                if (part.inlineData().isPresent()) {
                    String mime = part.inlineData().get().mimeType().orElse("");
                    if (mime.toLowerCase().startsWith("image/")) {
                        return true;
                    }
                }
                // Check fileData mimeType
                if (part.fileData().isPresent()) {
                    String mime = part.fileData().get().mimeType().orElse("");
                    if (mime.toLowerCase().startsWith("image/")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Baseline Task Demands Mapping
    // -------------------------------------------------------------------------

    private BaseDemands resolveBaseDemands(TaskType taskType) {
        return switch (taskType) {
            case SIMPLE_EDIT -> new BaseDemands(30, 70, 10, 30, 10, 512L);
            case FORMAT -> new BaseDemands(30, 75, 15, 60, 10, 2048L);
            case SUMMARIZE -> new BaseDemands(40, 70, 10, 30, 10, 2048L);
            case EXTRACT -> new BaseDemands(45, 75, 10, 60, 15, 2048L);
            case GENERAL_CHAT -> new BaseDemands(40, 60, 10, 20, 10, 2048L);
            case ANALYZE -> new BaseDemands(80, 80, 40, 50, 20, 4096L);
            case DIAGNOSE -> new BaseDemands(85, 80, 70, 50, 30, 4096L);
            case CODE_GENERATION -> new BaseDemands(75, 85, 85, 60, 40, 8192L);
            case DRAWIO_GENERATION -> new BaseDemands(65, 90, 50, 95, 80, 16384L);
            case DRAWIO_REVIEW -> new BaseDemands(75, 85, 65, 85, 35, 4096L);
            case STRUCTURED_GENERATION -> new BaseDemands(60, 85, 30, 90, 30, 4096L);
            case TOOL_ORCHESTRATION -> new BaseDemands(70, 85, 40, 70, 90, 4096L);
            default -> new BaseDemands(50, 60, 20, 30, 20, 2048L);
        };
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private RoutingRequirement defaultRequirement(String agentName) {
        return new RoutingRequirement(
                TaskType.UNKNOWN,
                50, 50, 20, 30, 20,
                false, 4096L, 2048L, agentName,
                RequirementEvidence.empty()
        );
    }

    private record BaseDemands(
            int reasoning,
            int instructionFollowing,
            int coding,
            int structuredOutput,
            int toolCalling,
            long expectedOutputTokens
    ) {}
}
