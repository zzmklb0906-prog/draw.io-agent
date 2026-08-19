package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import cn.bugstack.ai.domain.agent.service.llm.routing.context.RoutingContext;
import com.google.adk.models.LlmRequest;

/**
 * Strategy interface for LLM Model Routing.
 * Supports both high-level {@link RoutingContext} and legacy {@link LlmRequest} inputs.
 */
public interface IModelRouterStrategy {

    /**
     * Context-aware model routing entry point.
     */
    ModelRoutingService.Decision route(RoutingContext context, String fastModel, String balancedModel, String reasoningModel);

    /**
     * Legacy request-based overload.
     */
    ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel);

    String strategyName();
}

