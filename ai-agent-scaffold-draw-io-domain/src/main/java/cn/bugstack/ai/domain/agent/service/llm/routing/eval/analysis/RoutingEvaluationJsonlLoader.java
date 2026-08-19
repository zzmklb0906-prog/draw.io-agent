package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

import cn.bugstack.ai.domain.agent.service.llm.routing.eval.RoutingEvaluationRecord;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Robust JSONL dataset loader for offline routing evaluation analysis.
 */
@Slf4j
@Component
public class RoutingEvaluationJsonlLoader {

    private final ObjectMapper objectMapper;

    public RoutingEvaluationJsonlLoader() {
        this(new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    public RoutingEvaluationJsonlLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Loads evaluation records from a JSONL file at the specified path.
     */
    public List<RoutingEvaluationRecord> load(Path filePath) {
        if (filePath == null || !Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            log.warn("Evaluation JSONL file path is null or does not exist: {}", filePath);
            return Collections.emptyList();
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return readLines(reader);
        } catch (IOException e) {
            log.error("Failed to read evaluation dataset from [{}]: {}", filePath, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Loads evaluation records from an input stream (e.g. classpath resource or test fixture).
     */
    public List<RoutingEvaluationRecord> load(InputStream inputStream) {
        if (inputStream == null) {
            return Collections.emptyList();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return readLines(reader);
        } catch (IOException e) {
            log.error("Failed to read evaluation dataset from InputStream: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RoutingEvaluationRecord> readLines(BufferedReader reader) throws IOException {
        List<RoutingEvaluationRecord> records = new ArrayList<>();
        String line;
        long lineNum = 0;

        while ((line = reader.readLine()) != null) {
            lineNum++;
            if (StringUtils.isBlank(line)) {
                continue;
            }

            // Strip possible log prefixes (e.g. "[routing_shadow_eval] ")
            String json = line.trim();
            int prefixIdx = json.indexOf("[routing_shadow_eval]");
            if (prefixIdx >= 0) {
                json = json.substring(prefixIdx + "[routing_shadow_eval]".length()).trim();
            }

            try {
                RoutingEvaluationRecord record = objectMapper.readValue(json, RoutingEvaluationRecord.class);
                if (record != null) {
                    records.add(record);
                }
            } catch (Exception e) {
                log.warn("Skipping malformed evaluation record at line {}: {}", lineNum, e.getMessage());
            }
        }
        return Collections.unmodifiableList(records);
    }
}
