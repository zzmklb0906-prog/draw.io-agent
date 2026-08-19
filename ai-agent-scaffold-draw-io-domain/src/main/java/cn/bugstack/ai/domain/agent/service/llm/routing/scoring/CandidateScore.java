package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;

/**
 * Score evaluation result for an individual model candidate.
 */
public record CandidateScore(
        ModelProfile model,
        double totalScore,
        double estimatedCost,
        ScoreBreakdown breakdown
) {}
