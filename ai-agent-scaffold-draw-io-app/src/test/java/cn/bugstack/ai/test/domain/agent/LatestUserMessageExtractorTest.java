package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.extract.LatestUserMessageExtractor;
import cn.bugstack.ai.domain.agent.service.llm.routing.extract.RoutingTextInput;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LatestUserMessageExtractor}.
 *
 * <p>Validates single-turn extraction, multi-turn history isolation, multi-part text aggregation,
 * whole-context size counting, and current-turn user content discovery.</p>
 */
class LatestUserMessageExtractorTest {

    private LatestUserMessageExtractor extractor;

    @BeforeEach
    void setUp() {
        this.extractor = new LatestUserMessageExtractor();
    }

    // =========================================================================
    // Case 1 & 2: Null request / empty contents
    // =========================================================================

    @Test
    void case1_nullRequest_returnsEmpty() {
        assertEquals("", extractor.extract(null));
        assertEquals(0, extractor.totalContextChars(null));

        RoutingTextInput input = extractor.buildRoutingInput(null);
        assertNotNull(input);
        assertEquals("", input.latestUserText());
        assertEquals(0, input.totalContextChars());
        assertTrue(extractor.findLatestUserContent(null).isEmpty());
    }

    @Test
    void case2_emptyContents_returnsEmpty() {
        LlmRequest req = request();
        assertEquals("", extractor.extract(req));
        assertEquals(0, extractor.totalContextChars(req));

        RoutingTextInput input = extractor.buildRoutingInput(req);
        assertEquals("", input.latestUserText());
        assertEquals(0, input.totalContextChars());
        assertTrue(extractor.findLatestUserContent(req).isEmpty());
    }

    // =========================================================================
    // Case 3 & 4: Single user message / Multi-turn isolation
    // =========================================================================

    @Test
    void case3_singleUserMessage_extractsFullText() {
        LlmRequest req = request(userContent("画一个微信登录时序图"));
        assertEquals("画一个微信登录时序图", extractor.extract(req));
        assertEquals("画一个微信登录时序图".length(), extractor.totalContextChars(req));
    }

    @Test
    void case4_multiTurn_extractsOnlyLatestUserMessage() {
        LlmRequest req = request(
                userContent("第一轮：请设计一个高可用分布式架构方案并进行详细分析"),
                assistantContent("第一轮回答：架构方案包含网关、微服务、分布式缓存与数据库分库分表……"),
                userContent("把标题改成系统架构")
        );

        String latest = extractor.extract(req);
        assertEquals("把标题改成系统架构", latest,
                "Must extract ONLY the latest user message, ignoring earlier turns");

        int totalChars = extractor.totalContextChars(req);
        assertTrue(totalChars > latest.length(),
                "totalContextChars must include historical turns for capacity awareness");
    }

    // =========================================================================
    // findLatestUserContent & Multi-modal Parts
    // =========================================================================

    @Test
    void case5_findLatestUserContent_returnsLastUser() {
        Content user1 = userContent("第一轮问题");
        Content user2 = userContent("第二轮问题");
        LlmRequest req = request(user1, assistantContent("第一轮回答"), user2);

        Optional<Content> result = extractor.findLatestUserContent(req);
        assertTrue(result.isPresent());
        assertEquals("第二轮问题", extractor.extract(req));
    }

    @Test
    void case6_findLatestUserContent_ignoresTrailingAssistant() {
        Content user1 = userContent("当前用户问题");
        LlmRequest req = request(user1, assistantContent("助手中间响应"));

        Optional<Content> result = extractor.findLatestUserContent(req);
        assertTrue(result.isPresent());
        assertEquals("当前用户问题", extractor.extract(req));
    }

    @Test
    void case7_findLatestUserContent_noUserReturnsEmpty() {
        LlmRequest req = request(assistantContent("没有用户消息"), assistantContent("第二条助手消息"));
        assertTrue(extractor.findLatestUserContent(req).isEmpty());
        assertEquals("", extractor.extract(req));
    }

    @Test
    void case8_findLatestUserContent_withImageAndText_extractsOnlyText() {
        Part imgPart = Part.builder()
                .inlineData(Blob.builder().mimeType("image/png").data(new byte[]{1, 2}).build())
                .build();
        Part txtPart = Part.fromText("分析图表");
        Content multimodalUser = Content.builder().role("user").parts(List.of(imgPart, txtPart)).build();

        LlmRequest req = request(multimodalUser);
        Optional<Content> content = extractor.findLatestUserContent(req);
        assertTrue(content.isPresent());
        assertEquals(2, content.get().parts().get().size());

        // extract() should only extract the text Part
        assertEquals("分析图表", extractor.extract(req));
    }

    // =========================================================================
    // Case 9: Multi-part text aggregation
    // =========================================================================

    @Test
    void case9_multiPartMessage_concatenatesParts() {
        Content multiPart = Content.builder()
                .role("user")
                .parts(List.of(
                        Part.fromText("第一段说明"),
                        Part.fromText("第二段补充")
                ))
                .build();
        LlmRequest req = request(multiPart);

        String result = extractor.extract(req);
        assertTrue(result.contains("第一段说明"));
        assertTrue(result.contains("第二段补充"));
    }

    // =========================================================================
    // Case 10: Case-insensitive role matching
    // =========================================================================

    @Test
    void case10_caseInsensitiveRole_matchedCorrectly() {
        Content userUpper = Content.builder().role("USER").parts(List.of(Part.fromText("大写角色测试"))).build();
        LlmRequest req = request(userUpper);
        assertEquals("大写角色测试", extractor.extract(req));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static LlmRequest request(Content... contents) {
        return LlmRequest.builder().model("test").contents(List.of(contents)).build();
    }

    private static Content userContent(String text) {
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    private static Content assistantContent(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }
}
