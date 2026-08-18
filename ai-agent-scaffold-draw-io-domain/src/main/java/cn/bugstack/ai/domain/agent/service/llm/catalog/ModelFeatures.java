package cn.bugstack.ai.domain.agent.service.llm.catalog;

/**
 * Model Hard Features Support.
 *
 * <p>Represents binary or ternary feature support levels (SUPPORTED, UNSUPPORTED, UNKNOWN).
 * Unlike soft capability scores, features represent strict functional support flags
 * used for constraint filtering (e.g. Phase 4).</p>
 */
public record ModelFeatures(
        SupportStatus toolCalling,
        SupportStatus structuredOutput,
        SupportStatus vision
) {
    public static ModelFeatures defaultFeatures() {
        return new ModelFeatures(SupportStatus.UNKNOWN, SupportStatus.UNKNOWN, SupportStatus.UNKNOWN);
    }
}
