package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.ModelRoutingService;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelRoutingServiceTest {
    private final ModelRoutingService router=new ModelRoutingService(true,"fast","balanced","reasoning");

    @Test void routesSimpleFormattingToFastModel(){assertEquals("fast",router.route(request("请把这段内容进行摘要和格式整理")).model());}
    @Test void routesArchitectureAnalysisToReasoningModel(){assertEquals("reasoning",router.route(request("请完成跨模块架构分析并检查状态机调用链")).model());}
    @Test void routesNormalTaskToBalancedModel(){assertEquals("balanced",router.route(request("画一个用户登录流程图")).model());}

    private LlmRequest request(String text){return LlmRequest.builder().model("default").contents(List.of(Content.fromParts(Part.fromText(text)))).build();}
}
