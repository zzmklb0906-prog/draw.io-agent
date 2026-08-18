package cn.bugstack.ai.domain.agent.service.llm.strategy;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import com.google.adk.models.LlmRequest;

/**
 * Strategy interface for LLM Model Routing.
 */
public interface IModelRouterStrategy {
    ModelRoutingService.Decision route(LlmRequest request, String fastModel, String balancedModel, String reasoningModel);
    String strategyName();
}
