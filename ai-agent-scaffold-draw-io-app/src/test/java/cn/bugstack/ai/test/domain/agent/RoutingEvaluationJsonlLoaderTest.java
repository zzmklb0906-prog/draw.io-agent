package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecord;
import cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis.RoutingEvaluationJsonlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoutingEvaluationJsonlLoader}.
 */
class RoutingEvaluationJsonlLoaderTest {

    private RoutingEvaluationJsonlLoader loader;

    @BeforeEach
    void setUp() {
        this.loader = new RoutingEvaluationJsonlLoader();
    }

    @Test
    void loadFromStream_parsesValidJsonlAndSkipsMalformedLines() {
        String jsonl = """
                {"invocationId":"inv-1","agentName":"agent_analyst","taskType":"GENERAL_CHAT","actualModel":"qwen3.7-plus","recommendedModel":"qwen3.7-plus","matched":true}
                
                {"invocationId":"inv-2","agentName":"agent_drawer","taskType":"DRAWIO_GENERATION","actualModel":"qwen3.7-plus","recommendedModel":"qwen3.8-max","matched":false}
                MALFORMED JSON LINE THAT SHOULD BE SKIPPED
                [routing_shadow_eval] {"invocationId":"inv-3","agentName":"agent_reviewer","taskType":"CODE_GENERATION","actualModel":"qwen3.7-flash","recommendedModel":"qwen3.7-flash","matched":true}
                """;

        List<RoutingEvaluationRecord> records = loader.load(new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, records.size());
        assertEquals("inv-1", records.get(0).invocationId());
        assertEquals("inv-2", records.get(1).invocationId());
        assertEquals("inv-3", records.get(2).invocationId());
    }

    @Test
    void loadFromFile_readsRecordsSafely(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("routing-eval-test.jsonl");
        String content = """
                {"invocationId":"inv-f1","agentName":"agent","taskType":"ANALYZE","actualModel":"qwen3.7-plus","recommendedModel":"qwen3.7-plus","matched":true}
                """;
        Files.writeString(testFile, content, StandardCharsets.UTF_8);

        List<RoutingEvaluationRecord> records = loader.load(testFile);

        assertEquals(1, records.size());
        assertEquals("inv-f1", records.get(0).invocationId());
    }

    @Test
    void loadFromNonExistentPath_returnsEmptyList() {
        List<RoutingEvaluationRecord> records = loader.load(Path.of("non-existent-eval-file.jsonl"));
        assertNotNull(records);
        assertTrue(records.isEmpty());
    }
}
