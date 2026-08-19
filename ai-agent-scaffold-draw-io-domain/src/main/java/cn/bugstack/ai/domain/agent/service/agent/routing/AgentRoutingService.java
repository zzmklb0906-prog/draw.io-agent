package cn.bugstack.ai.domain.agent.service.agent.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Universal Agent Routing Service.
 *
 * <p><strong>Hierarchical Routing Level 1:</strong>
 * Determines which autonomous Agent handles the task before Level 2 (Model Router) selects the LLM.</p>
 */
@Slf4j
@Service
public class AgentRoutingService {

    private final AgentRequirementAnalyzer analyzer;
    private final AgentSelector selector;
    private final AgentRankingEngine rankingEngine;

    public AgentRoutingService(AgentRequirementAnalyzer analyzer,
                               AgentSelector selector,
                               AgentRankingEngine rankingEngine) {
        this.analyzer = analyzer;
        this.selector = selector;
        this.rankingEngine = rankingEngine;
    }

    /**
     * Routes an incoming user prompt to the best suited Agent.
     *
     * @param prompt user prompt text
     * @return authoritative {@link AgentDecision}
     */
    public AgentDecision route(String prompt) {
        // Step 1: Analyze user request into abstract agent requirement
        AgentRequirement requirement = analyzer != null
                ? analyzer.analyze(prompt)
                : AgentRequirement.generalChat(prompt);

        log.debug("Deduced AgentRequirement: capabilities={}, tasks={}", requirement.requiredCapabilities(), requirement.taskTypes());

        // Step 2: Select candidate agents matching capabilities
        List<AgentProfile> candidates = selector != null
                ? selector.select(requirement)
                : List.of();

        if (candidates.isEmpty()) {
            log.warn("No suitable Agent found in registry for prompt: {}", prompt);
            return AgentDecision.notFound("No suitable Agent registered matching required capabilities: " + requirement.requiredCapabilities());
        }

        // Step 3: Rank candidates
        List<RankedAgent> ranked = rankingEngine != null
                ? rankingEngine.rank(requirement, candidates)
                : List.of();

        if (ranked.isEmpty()) {
            return AgentDecision.notFound("No active candidate agents available after ranking");
        }

        RankedAgent top1 = ranked.get(0);
        String selectedAgentId = top1.profile().agentId();
        double confidence = top1.score();
        List<String> backupAgents = new ArrayList<>();

        if (ranked.size() > 1) {
            confidence = Math.round((top1.score() - ranked.get(1).score()) * 100.0) / 100.0;
            for (int i = 1; i < ranked.size(); i++) {
                backupAgents.add(ranked.get(i).profile().agentId());
            }
        }

        log.info("Agent Routing Decision: selectedAgent=[{}] (confidence={}, score={}), backups={}",
                selectedAgentId, confidence, top1.score(), backupAgents);

        return new AgentDecision(
                selectedAgentId,
                confidence,
                top1.reason(),
                backupAgents,
                true
        );
    }
}
