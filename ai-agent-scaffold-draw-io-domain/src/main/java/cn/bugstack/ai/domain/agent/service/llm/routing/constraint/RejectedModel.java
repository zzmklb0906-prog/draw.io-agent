package cn.bugstack.ai.domain.agent.service.llm.routing.constraint;

import cn.bugstack.ai.domain.agent.service.llm.catalog.ModelProfile;

import java.util.List;

/**
 * Record representing a rejected model candidate along with all accumulated violations.
 */
public record RejectedModel(
        ModelProfile model,
        List<ConstraintViolation> violations
) {}
