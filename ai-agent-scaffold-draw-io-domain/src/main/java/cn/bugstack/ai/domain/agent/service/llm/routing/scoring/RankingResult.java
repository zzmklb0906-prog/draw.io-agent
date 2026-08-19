package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

import java.util.List;
import java.util.Optional;

/**
 * Result of Dynamic Model Ranking.
 *
 * <p>Contains an immutable list of ranked candidate models along with convenience methods
 * to access the top recommended model. Does NOT alter active runtime model execution.</p>
 */
public record RankingResult(
        List<CandidateScore> rankedCandidates
) {
    public RankingResult {
        rankedCandidates = rankedCandidates != null ? List.copyOf(rankedCandidates) : List.of();
    }

    /**
     * Returns the highest-scoring candidate model recommendation, if available.
     */
    public Optional<CandidateScore> topCandidate() {
        return rankedCandidates.isEmpty() ? Optional.empty() : Optional.of(rankedCandidates.get(0));
    }

    /**
     * Alias for {@link #topCandidate()}.
     */
    public Optional<CandidateScore> recommendedCandidate() {
        return topCandidate();
    }

    public boolean isEmpty() {
        return rankedCandidates.isEmpty();
    }

    public static RankingResult empty() {
        return new RankingResult(List.of());
    }
}
