package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service for analyzing routing requirements in Shadow Mode.
 *
 * <p>Safely wraps {@link RoutingRequirementAnalyzer} execution, ensuring requirement
 * analysis never throws or interrupts normal LLM invocation paths.</p>
 */
@Slf4j
@Service
public class RoutingRequirementService {

    private final RoutingRequirementAnalyzer analyzer;

    public RoutingRequirementService(RoutingRequirementAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * Detailed result of requirement analysis indicating success or failure.
     */
    public record RequirementAnalysisResult(
            Status status,
            RoutingRequirement requirement,
            String errorMessage
    ) {
        public enum Status {
            SUCCESS,
            FAILED
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS && requirement != null;
        }

        public static RequirementAnalysisResult success(RoutingRequirement requirement) {
            return new RequirementAnalysisResult(Status.SUCCESS, requirement, null);
        }

        public static RequirementAnalysisResult failure(String errorMessage) {
            return new RequirementAnalysisResult(Status.FAILED, null, errorMessage);
        }
    }

    /**
     * Attempts detailed requirement analysis without throwing exceptions.
     */
    public RequirementAnalysisResult analyzeDetailed(RoutingContext context) {
        try {
            if (context == null) {
                return RequirementAnalysisResult.failure("RoutingContext is null");
            }
            RoutingRequirement requirement = analyzer.analyze(context);
            return RequirementAnalysisResult.success(requirement);
        } catch (Exception e) {
            log.warn("Shadow routing requirement analysis encountered an error (non-fatal): {}", e.getMessage());
            return RequirementAnalysisResult.failure(e.getMessage());
        }
    }

    /**
     * Attempts to analyze requirement; returns {@link Optional#empty()} if analysis fails.
     * Prevents synthetic fallbacks from being passed into Hard Constraint Filtering.
     */
    public Optional<RoutingRequirement> tryAnalyze(RoutingContext context) {
        RequirementAnalysisResult result = analyzeDetailed(context);
        return result.isSuccess() ? Optional.of(result.requirement()) : Optional.empty();
    }

    /**
     * Legacy/convenience analysis returning a synthetic fallback requirement on failure.
     */
    public RoutingRequirement analyze(RoutingContext context) {
        return tryAnalyze(context).orElseGet(() -> new RoutingRequirement(
                TaskType.UNKNOWN,
                50, 50, 20, 30, 20,
                false, 4096L, 2048L,
                context != null ? context.agentName() : "unknown",
                RequirementEvidence.empty()
        ));
    }
}
