package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Current-turn Multimodal Vision Detector.
 *
 * <p>Determines whether the <strong>current turn</strong> requires vision capability.
 * Strictly inspects ONLY the latest user-role {@link Content} in {@link LlmRequest}.
 * Historical image Parts in previous turns (user, assistant, or tool) are ignored.</p>
 */
@Component
public class CurrentTurnVisionDetector {

    private final LatestUserMessageExtractor extractor;

    public CurrentTurnVisionDetector(LatestUserMessageExtractor extractor) {
        this.extractor = extractor;
    }

    /**
     * Checks if the latest user message in {@code request} contains any image Part.
     *
     * @param request the LLM request (may be null)
     * @return {@code true} if and only if the current user turn contains an image Part
     */
    public boolean requiresVision(LlmRequest request) {
        if (request == null) {
            return false;
        }

        Optional<Content> latestUserContent = extractor.findLatestUserContent(request);
        if (latestUserContent.isEmpty()) {
            return false;
        }

        List<Part> parts = latestUserContent.get().parts().orElse(List.of());
        for (Part part : parts) {
            if (part == null) {
                continue;
            }
            // 1. Check inlineData blob mimeType
            if (part.inlineData().isPresent()) {
                String mime = part.inlineData().get().mimeType().orElse("");
                if (mime.toLowerCase().startsWith("image/")) {
                    return true;
                }
            }
            // 2. Check fileData mimeType
            if (part.fileData().isPresent()) {
                String mime = part.fileData().get().mimeType().orElse("");
                if (mime.toLowerCase().startsWith("image/")) {
                    return true;
                }
            }
        }

        return false;
    }
}
