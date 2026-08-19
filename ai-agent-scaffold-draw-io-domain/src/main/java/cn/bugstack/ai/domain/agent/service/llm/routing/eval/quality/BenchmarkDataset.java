package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import java.util.List;

/**
 * Versioned container of benchmark evaluation cases.
 */
public record BenchmarkDataset(
        String datasetId,
        String version,
        List<BenchmarkCase> cases
) {
    public BenchmarkDataset {
        cases = cases != null ? List.copyOf(cases) : List.of();
    }
}
