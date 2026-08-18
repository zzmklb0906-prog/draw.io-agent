package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
     * Performs safe shadow analysis of the routing context.
     *
     * @param context the invocation context
     * @return the resolved {@link RoutingRequirement}
     */
    public RoutingRequirement analyze(RoutingContext context) {
        try {
            return analyzer.analyze(context);
        } catch (Exception e) {
            log.warn("Shadow routing requirement analysis encountered an error (non-fatal): {}", e.getMessage());
            return new RoutingRequirement(
                    TaskType.UNKNOWN,
                    50, 50, 20, 30, 20,
                    false, 4096L, 2048L,
                    context != null ? context.agentName() : "unknown",
                    RequirementEvidence.empty()
            );
        }
    }
}
