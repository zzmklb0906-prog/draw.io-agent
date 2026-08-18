package cn.bugstack.ai.domain.agent.service.llm.routing.extract;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * LatestUserMessageExtractor
 *
 * <p>Single-responsibility component: extracts the <strong>last user-role message</strong>
 * from an {@link LlmRequest}, so routing decisions are based on the current user intent
 * rather than the entire multi-turn conversation history.</p>
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Iterates contents in reverse order — the last user Content wins.</li>
 *   <li>Exposes {@link #findLatestUserContent(LlmRequest)} as the single source of truth for current-turn extraction.</li>
 *   <li>Concatenates all text Parts within that Content (handles multi-part messages).</li>
 *   <li>Returns an empty string (never throws) when no user Content is found.</li>
 *   <li>Computes whole-context size via {@link #totalContextChars(LlmRequest)} and wraps both into {@link RoutingTextInput}.</li>
 * </ul>
 * </p>
 */
@Component
public class LatestUserMessageExtractor {

    /**
     * Unified factory method: builds a {@link RoutingTextInput} from an {@link LlmRequest}.
     *
     * <p>Extracts the latest user message for intent & complexity analysis, and computes
     * the total character count of the whole context for window-budget awareness.</p>
     *
     * @param request the incoming LLM request (may be null)
     * @return a safe {@link RoutingTextInput} instance (never null)
     */
    public RoutingTextInput buildRoutingInput(LlmRequest request) {
        if (request == null || request.contents() == null || request.contents().isEmpty()) {
            return RoutingTextInput.empty();
        }
        return new RoutingTextInput(
                extract(request),
                totalContextChars(request)
        );
    }

    /**
     * Finds the last user-role {@link Content} in {@code request} (scanning in reverse order).
     *
     * @param request the incoming LLM request (may be null)
     * @return Optional containing the last user Content, or empty if none found
     */
    public Optional<Content> findLatestUserContent(LlmRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        List<Content> contents = request.contents();
        if (contents == null || contents.isEmpty()) {
            return Optional.empty();
        }

        // Reverse scan: find the last Content with role="user"
        for (int i = contents.size() - 1; i >= 0; i--) {
            Content content = contents.get(i);
            if (isUserRole(content)) {
                return Optional.of(content);
            }
        }

        return Optional.empty();
    }

    /**
     * Extracts the text of the last user-role Content in {@code request}.
     *
     * @param request the incoming LLM request (may be null)
     * @return the text of the last user message, or {@code ""} if none found
     */
    public String extract(LlmRequest request) {
        return findLatestUserContent(request)
                .map(this::extractText)
                .orElse("");
    }

    /**
     * Computes the approximate total character count of all contents — used
     * as the "whole context size" metric, separate from the latest user text.
     *
     * @param request the incoming LLM request (may be null)
     * @return total character count across all parts of all contents
     */
    public int totalContextChars(LlmRequest request) {
        if (request == null) {
            return 0;
        }
        List<Content> contents = request.contents();
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (Content content : contents) {
            total += extractText(content).length();
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean isUserRole(Content content) {
        if (content == null) {
            return false;
        }
        return content.role()
                .map(role -> "user".equalsIgnoreCase(role.trim()))
                .orElse(false);
    }

    /**
     * Concatenates all text Parts inside a Content.
     * Uses the {@code Part.text()} Optional — skips Parts without text (e.g., image blobs, function responses).
     */
    private String extractText(Content content) {
        if (content == null) {
            return "";
        }

        List<Part> parts = content.parts().orElse(List.of());
        if (parts.isEmpty()) {
            return content.text() != null ? content.text() : "";
        }

        StringBuilder sb = new StringBuilder();
        for (Part part : parts) {
            part.text().ifPresent(t -> {
                if (!t.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(t);
                }
            });
        }
        return sb.toString();
    }
}
