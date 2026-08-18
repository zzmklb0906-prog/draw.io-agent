package cn.bugstack.ai.domain.agent.service.llm.routing.extract;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.stereotype.Component;

import java.util.List;

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
 *   <li>Concatenates all text Parts within that Content (handles multi-part messages).</li>
 *   <li>Returns an empty string (never throws) when no user Content is found.</li>
 *   <li>Does NOT compute whole-context size; that belongs to {@link RoutingTextInput}.</li>
 * </ul>
 * </p>
 *
 * <p><strong>ADK / GenAI API note (verified against google-genai 1.58.0 and google-adk 1.7.0):</strong>
 * <ul>
 *   <li>{@code Content.role()} → {@code Optional<String>}</li>
 *   <li>{@code Content.parts()} → {@code Optional<List<Part>>}</li>
 *   <li>{@code Part.text()} → {@code Optional<String>}</li>
 *   <li>{@code Content.text()} → {@code String} (convenience aggregation, may be empty)</li>
 * </ul>
 * </p>
 */
@Component
public class LatestUserMessageExtractor {

    /**
     * Extracts the text of the last user-role Content in {@code request}.
     *
     * @param request the incoming LLM request (may be null)
     * @return the text of the last user message, or {@code ""} if none found
     */
    public String extract(LlmRequest request) {
        if (request == null) {
            return "";
        }

        List<Content> contents = request.contents();
        if (contents == null || contents.isEmpty()) {
            return "";
        }

        // Scan from the end to find the last user Content
        for (int i = contents.size() - 1; i >= 0; i--) {
            Content content = contents.get(i);
            if (isUserRole(content)) {
                return extractText(content);
            }
        }

        return "";
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
        // content.role() returns Optional<String>; "user" is the standard role identifier
        return content.role()
                .map(role -> "user".equalsIgnoreCase(role.trim()))
                .orElse(false);
    }

    /**
     * Concatenates all text Parts inside a Content.
     * Uses the {@code Part.text()} Optional — skips Parts without text (e.g., function responses).
     */
    private String extractText(Content content) {
        if (content == null) {
            return "";
        }

        // Fast path: use the built-in text() aggregation if available
        // Content.text() returns a String (may be empty if no text parts)
        // We prefer iterating parts to concatenate with newlines for readability
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
