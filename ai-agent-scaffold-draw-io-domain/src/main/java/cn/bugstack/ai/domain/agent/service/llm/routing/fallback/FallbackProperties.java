package cn.bugstack.ai.domain.agent.service.llm.routing.fallback;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Fallback Execution.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.model-routing.fallback")
public class FallbackProperties {

    private boolean enabled = true;

    /**
     * Maximum cumulative invocation attempts (e.g. 2 means Primary + 1 Backup maximum).
     */
    private int maxAttempts = 2;
}
