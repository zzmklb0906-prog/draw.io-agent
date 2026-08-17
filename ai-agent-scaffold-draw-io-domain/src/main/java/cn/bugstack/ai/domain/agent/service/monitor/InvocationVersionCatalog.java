package cn.bugstack.ai.domain.agent.service.monitor;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InvocationVersionCatalog {
    private final Map<String,Map<String,Object>> versions=new ConcurrentHashMap<>();
    public void register(String appName,AiAgentConfigTableVO config){
        Map<String,Object> snapshot=new LinkedHashMap<>();
        String configJson=JSON.toJSONString(config),promptJson=JSON.toJSONString(config.getModule().getAgents());
        String model=config.getModule().getChatModel().getModel();
        snapshot.put("agentConfigVersion",fingerprint(configJson));snapshot.put("promptVersion",fingerprint(promptJson));
        snapshot.put("model",model);snapshot.put("modelVersion",fingerprint(model+"|"+config.getModule().getAiApi().getBaseUrl()+"|"+config.getModule().getAiApi().getCompletionsPath()));
        snapshot.put("capturedAt",System.currentTimeMillis());versions.put(appName,Map.copyOf(snapshot));
    }
    public Map<String,Object> snapshot(String appName){return versions.getOrDefault(appName,Map.of());}
    public static String fingerprint(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,16);}catch(Exception e){throw new IllegalStateException(e);}}
}
