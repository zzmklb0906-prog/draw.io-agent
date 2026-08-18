package cn.bugstack.ai.domain.agent.service.llm.routing.extract;

/**
 * RoutingTextInput
 *
 * <p>Value object that separates two historically conflated concerns:</p>
 * <ul>
 *   <li>{@link #latestUserText()} — the <em>current</em> user message only.
 *       Used for: keyword detection, complexity estimation, intent analysis.</li>
 *   <li>{@link #totalContextChars()} — total char count of the full conversation history.
 *       Used for: context-window budget estimation (NOT for keyword-based routing).</li>
 * </ul>
 *
 * <p>This separation is the core fix for Phase 1: previously all three Routers called
 * {@code String.valueOf(request.contents())} and fed the entire conversation history
 * into keyword matching, causing historical complex keywords to escalate simple
 * follow-up requests.</p>
 *
 * <p>Created by {@link LatestUserMessageExtractor}; consumed by all Router strategies
 * via {@link cn.bugstack.ai.domain.agent.service.llm.strategy.IModelRouterStrategy}.</p>
 */
public record RoutingTextInput(
        String latestUserText,
        int totalContextChars
) {
    /** Convenience factory returning empty / safe defaults. */
    public static RoutingTextInput empty() {
        return new RoutingTextInput("", 0);
    }
}
