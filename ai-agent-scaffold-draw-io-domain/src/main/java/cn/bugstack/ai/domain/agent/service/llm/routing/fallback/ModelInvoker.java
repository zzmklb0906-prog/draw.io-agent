package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import cn.bugstack.ai.domain.agent.service.llm.routing.runtime.ModelExecutionResult;

/**
 * Functional interface abstracting synchronous execution of a selected model.
 */
@FunctionalInterface
public interface ModelInvoker {

    /**
     * Executes the invocation with the given model.
     *
     * @param modelName name or ID of the model to invoke
     * @return {@link ModelExecutionResult} raw execution telemetry
     */
    ModelExecutionResult invoke(String modelName);
}
