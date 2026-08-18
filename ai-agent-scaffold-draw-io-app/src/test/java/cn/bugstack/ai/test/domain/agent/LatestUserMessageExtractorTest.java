package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.RoutingTextInput;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LatestUserMessageExtractor}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The extractor returns only the last user-role message, not the full history.</li>
 *   <li>Null/empty inputs are handled safely (no NullPointerException).</li>
 *   <li>Multi-part Content is properly concatenated.</li>
 *   <li>totalContextChars counts all content, not just user messages.</li>
 * </ul>
 */
class LatestUserMessageExtractorTest {

    private final LatestUserMessageExtractor extractor = new LatestUserMessageExtractor();

    // -------------------------------------------------------------------------
    // Basic extraction
    // -------------------------------------------------------------------------

    @Test
    void extractsLatestUserMessage_singleTurn() {
        LlmRequest request = request(userContent("你好"));
        String result = extractor.extract(request);
        assertEquals("你好", result);
    }

    @Test
    void extractsLatestUserMessage_multiTurn_returnsLastUserOnly() {
        // Multi-turn: complex first message + simple second message
        // This is the core regression: should NOT return the full history
        LlmRequest request = LlmRequest.builder()
                .model("test")
                .contents(List.of(
                        userContent("请深入分析整个系统架构、状态机、并发模型以及一致性"),
                        assistantContent("（很长的架构分析回复）... 系统架构包含多个核心模块..."),
                        userContent("把标题改成登录流程")  // ← this is the latest user message
                ))
                .build();

        String result = extractor.extract(request);

        // Must return ONLY the last user message
        assertEquals("把标题改成登录流程", result);
        assertFalse(result.contains("架构"), "Multi-turn extraction must NOT contain first-turn history keywords");
        assertFalse(result.contains("状态机"), "Must NOT contaminate with first-turn assistant-provided content");
    }

    @Test
    void extractsLatestUserMessage_skipsAssistantMessages() {
        LlmRequest request = LlmRequest.builder()
                .model("test")
                .contents(List.of(
                        userContent("第一轮问题"),
                        assistantContent("第一轮回答"),
                        userContent("第二轮问题"),
                        assistantContent("第二轮回答")  // ← last message is assistant, not user
                ))
                .build();

        String result = extractor.extract(request);

        // Should find the last USER message, not the assistant's last message
        assertEquals("第二轮问题", result);
    }

    // -------------------------------------------------------------------------
    // Context size
    // -------------------------------------------------------------------------

    @Test
    void totalContextChars_countsAllContents() {
        LlmRequest request = LlmRequest.builder()
                .model("test")
                .contents(List.of(
                        userContent("12345"),       // 5 chars
                        assistantContent("67890")   // 5 chars
                ))
                .build();

        int total = extractor.totalContextChars(request);
        assertEquals(10, total, "Should count chars across all messages, not just user messages");
    }

    @Test
    void totalContextChars_doesNotEqualLatestUserLen() {
        // Demonstrates the separation of concerns:
        // latestUserText.length() != totalContextChars()
        String longHistory = "A".repeat(5000);
        LlmRequest request = LlmRequest.builder()
                .model("test")
                .contents(List.of(
                        userContent(longHistory),          // 5000 chars - first turn complex
                        assistantContent("回答"),           // 2 chars
                        userContent("修改标题")              // 4 chars - latest user message
                ))
                .build();

        String latest = extractor.extract(request);
        int contextChars = extractor.totalContextChars(request);

        assertEquals("修改标题", latest, "Latest user text should be the current short message");
        assertTrue(contextChars > 5000, "Context chars should include full history");
        assertTrue(latest.length() < contextChars, "Latest user text is much shorter than total context");
    }

    // -------------------------------------------------------------------------
    // Null / empty safety
    // -------------------------------------------------------------------------

    @Test
    void returnsEmpty_whenRequestIsNull() {
        assertDoesNotThrow(() -> {
            String result = extractor.extract(null);
            assertEquals("", result);
        });
    }

    @Test
    void returnsEmpty_whenContentsIsEmpty() {
        LlmRequest request = LlmRequest.builder().model("test").contents(List.of()).build();
        String result = extractor.extract(request);
        assertEquals("", result);
    }

    @Test
    void returnsEmpty_whenNoUserContentExists() {
        // Only assistant messages, no user message
        LlmRequest request = LlmRequest.builder()
                .model("test")
                .contents(List.of(assistantContent("系统提示或工具结果")))
                .build();

        String result = extractor.extract(request);
        assertEquals("", result, "Should safely return empty when no user message is present");
    }

    @Test
    void totalContextChars_returnsZero_whenNull() {
        assertEquals(0, extractor.totalContextChars(null));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LlmRequest request(Content... contents) {
        return LlmRequest.builder().model("test").contents(List.of(contents)).build();
    }

    private static Content userContent(String text) {
        // Content.fromParts sets no role — we need role=user explicitly
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    private static Content assistantContent(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }
}
