package cn.bugstack.ai.domain.agent.memory.service;

import cn.bugstack.ai.domain.agent.memory.model.entity.AgentMemoryEntity;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Extracts only explicit, reviewable user statements; it never infers hidden preferences. */
@Service
public class ConversationMemoryExtractionService {
    private static final Pattern SECRET=Pattern.compile("(?i)(api[_-]?key|token|secret|password|密码|密钥)\\s*[:=：]\\s*[^\\s,;，；]+|sk-[a-z0-9_-]{12,}");
    private final MemoryService memories;
    public ConversationMemoryExtractionService(MemoryService memories){this.memories=memories;}
    public void extractExplicitStatement(String userId,String sessionId,String message){if(userId==null||message==null)return;String text=message.trim();if(text.length()<4||text.length()>800||SECRET.matcher(text).find())return;String type=classify(text);if(type==null)return;String normalized=text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+","");boolean duplicate=memories.list(userId,null,type,100).stream().anyMatch(m->m.getContent().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+","").equals(normalized));if(duplicate)return;boolean lowRiskFact="PROJECT_FACT".equals(type);memories.create(AgentMemoryEntity.builder().userId(userId).memoryType(type).content(text).importance(type.equals("USER_PREFERENCE")?.7:.65).confidence(lowRiskFact?.9:.75).confirmed(lowRiskFact).sourceSessionId(sessionId).build());}
    private String classify(String text){String lower=text.toLowerCase(Locale.ROOT);if(lower.contains("我偏好")||lower.contains("我希望")||lower.contains("请默认")||lower.contains("以后请")||lower.contains("i prefer"))return "USER_PREFERENCE";if(lower.contains("本项目使用")||lower.contains("项目使用")||lower.contains("项目基于")||lower.contains("验证命令")||lower.contains("启动命令"))return "PROJECT_FACT";return null;}
}
