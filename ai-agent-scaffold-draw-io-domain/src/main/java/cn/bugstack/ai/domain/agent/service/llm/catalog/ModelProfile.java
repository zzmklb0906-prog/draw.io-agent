package cn.bugstack.ai.domain.agent.service.llm.catalog;

/**
 * Model Profile — Domain Model for Model Catalog.
 *
 * <p>Represents the full static identity, capability scores, feature support, token limits,
 * and pricing metadata for a registered LLM model.</p>
 *
 * <p><strong>Architectural Boundaries:</strong>
 * <ul>
 *   <li>{@code ModelProfile} answers <strong>WHAT</strong> a model can do and its operational constraints.</li>
 *   <li>{@code ModelProviderRegistryService} answers <strong>WHERE / HOW</strong> to connect (endpoints, credentials).</li>
 *   <li>{@code ModelRoutingService} answers <strong>WHY</strong> a specific model is chosen.</li>
 * </ul>
 * </p>
 */
public record ModelProfile(
        String id,
        String provider,
        String modelName,
        boolean enabled,
        ModelCapabilities capabilities,
        ModelFeatures features,
        ModelLimits limits,
        ModelPricing pricing
) {
    public boolean supportsToolCalling() {
        return features != null && features.toolCalling() == SupportStatus.SUPPORTED
                || (capabilities != null && capabilities.toolCalling() > 0);
    }

    public boolean supportsVision() {
        return features != null && features.vision() == SupportStatus.SUPPORTED
                || (capabilities != null && capabilities.vision() > 0);
    }

    public boolean supportsStructuredOutput() {
        return features != null && features.structuredOutput() == SupportStatus.SUPPORTED
                || (capabilities != null && capabilities.structuredOutput() > 0);
    }

    public long contextWindow() {
        return limits != null ? limits.contextWindowTokens() : 0L;
    }

    public long maxOutputTokens() {
        return limits != null ? limits.maxOutputTokens() : 0L;
    }
}
