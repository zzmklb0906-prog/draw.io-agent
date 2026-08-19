package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

/**
 * Head-to-head competition metrics between Top-1 and Top-2 model pairs.
 */
public record ModelCompetitionPair(
        String top1Model,
        String top2Model,
        long count,
        double averageMargin
) {}
