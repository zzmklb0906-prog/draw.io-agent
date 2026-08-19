package cn.bugstack.ai.domain.agent.service.llm.routing.scoring;

/**
 * Record holding shadow routing comparison between production actual model and dynamic recommended model.
 */
public record RoutingShadowComparison(
        String actualModel,
        String recommendedModel,
        Boolean matched,
        Double recommendedScore,
        SelectionSource actualSource
) {
    public enum SelectionSource {
        LEGACY_ROUTER,
        USER_EXPLICIT
    }
}
