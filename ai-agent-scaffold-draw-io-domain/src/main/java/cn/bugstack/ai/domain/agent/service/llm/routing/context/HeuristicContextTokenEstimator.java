package cn.bugstack.ai.domain.agent.service.llm.routing.context;

import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import com.google.adk.models.LlmRequest;
import org.springframework.stereotype.Component;

/**
 * Heuristic token estimator for mixed Chinese/English LLM context.
 *
 * <p>Uses a fast rule-of-thumb ratio (approximately 1.8 characters per token for mixed
 * Chinese and English text) on {@code totalContextChars}.
 * This estimator is null-safe, empty-safe, and always returns a non-negative value.</p>
 */
@Component
public class HeuristicContextTokenEstimator implements ContextTokenEstimator {

    private static final double CHARS_PER_TOKEN = 1.8;
    private final LatestUserMessageExtractor extractor;

    public HeuristicContextTokenEstimator(LatestUserMessageExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public long estimate(LlmRequest request) {
        if (request == null) {
            return 0L;
        }
        int totalChars = extractor.totalContextChars(request);
        if (totalChars <= 0) {
            return 0L;
        }
        return (long) Math.ceil(totalChars / CHARS_PER_TOKEN);
    }
}
