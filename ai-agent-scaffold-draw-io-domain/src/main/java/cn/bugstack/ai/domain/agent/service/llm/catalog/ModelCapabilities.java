package cn.bugstack.ai.domain.agent.service.llm.catalog;

/**
 * Model Capabilities (Soft calibration scores: 0 ~ 100).
 *
 * <p><strong>IMPORTANT — Calibration Note:</strong>
 * These scores are <em>Routing Calibration Scores</em> used internally for multi-criteria
 * model scoring and ranking. They are NOT vendor official benchmarks, official capability rankings,
 * or absolute performance metrics. They serve as initial project calibration values and can be
 * tuned via Agent Eval and runtime execution observation.</p>
 */
public record ModelCapabilities(
        int reasoning,
        int instructionFollowing,
        int coding,
        int structuredOutput,
        int toolCalling,
        int vision,
        int longContext
) {
    public static ModelCapabilities defaultCapabilities() {
        return new ModelCapabilities(50, 50, 50, 50, 50, 0, 50);
    }
}
