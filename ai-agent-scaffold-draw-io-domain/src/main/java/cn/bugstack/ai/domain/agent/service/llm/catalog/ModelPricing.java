package cn.bugstack.ai.domain.agent.service.llm.catalog;

import java.math.BigDecimal;

/**
 * Model Token Pricing Metadata.
 *
 * <p>Pricing per 1M (one million) tokens.</p>
 * <p>Unknown pricing is represented as {@code null}. Non-null values must be &gt;= 0.</p>
 */
public record ModelPricing(
        BigDecimal inputPerMillionTokens,
        BigDecimal outputPerMillionTokens,
        String currency
) {
    public static ModelPricing unknown() {
        return new ModelPricing(null, null, "CNY");
    }
}
