package cn.bugstack.ai.domain.agent.service.llm;

import com.google.adk.models.LlmRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Deterministic, zero-token model router. Explicit user model selection always wins. */
@Service
public class ModelRoutingService {
    private final boolean enabled;
    private final String fastModel;
    private final String balancedModel;
    private final String reasoningModel;

    public ModelRoutingService(@Value("${ai.agent.model-routing.enabled:true}") boolean enabled,
                               @Value("${ai.agent.model-routing.fast-model:}") String fastModel,
                               @Value("${ai.agent.model-routing.balanced-model:}") String balancedModel,
                               @Value("${ai.agent.model-routing.reasoning-model:}") String reasoningModel) {
        this.enabled=enabled;this.fastModel=fastModel;this.balancedModel=balancedModel;this.reasoningModel=reasoningModel;
    }

    public Decision route(LlmRequest request) {
        if(!enabled)return new Decision(null,"DISABLED",0);
        String text=String.valueOf(request.contents());int length=text.length();String lower=text.toLowerCase();
        boolean complex=length>12_000||containsAny(lower,"架构","跨模块","根因","安全审计","重构","checkpoint","state machine","调用链");
        boolean simple=length<2_000&&containsAny(lower,"摘要","改写","标题","格式","校验","翻译","总结");
        if(complex&&!reasoningModel.isBlank())return new Decision(reasoningModel,"COMPLEX_REASONING",3);
        if(simple&&!fastModel.isBlank())return new Decision(fastModel,"LOW_COMPLEXITY",1);
        if(!balancedModel.isBlank())return new Decision(balancedModel,"BALANCED_DEFAULT",2);
        return new Decision(null,"KEEP_AGENT_DEFAULT",2);
    }

    private boolean containsAny(String text,String... terms){for(String term:terms)if(text.contains(term))return true;return false;}
    public record Decision(String model,String reason,int complexity){}
}
