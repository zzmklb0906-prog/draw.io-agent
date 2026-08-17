package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.memory.model.entity.AgentMemoryEntity;
import cn.bugstack.ai.domain.agent.memory.service.ConversationMemoryExtractionService;
import cn.bugstack.ai.domain.agent.memory.service.MemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationMemoryExtractionServiceTest {
    @Test void extractsExplicitPreferenceButRejectsSecret(){MemoryService memories=mock(MemoryService.class);when(memories.list(anyString(),isNull(),anyString(),anyInt())).thenReturn(List.of());ConversationMemoryExtractionService extractor=new ConversationMemoryExtractionService(memories);extractor.extractExplicitStatement("admin","s-1","我希望以后默认使用中文并采用 DDD 架构");verify(memories).list(eq("admin"),isNull(),eq("USER_PREFERENCE"),eq(100));verify(memories).create(argThat((AgentMemoryEntity m)->m.getMemoryType().equals("USER_PREFERENCE")&&!m.isConfirmed()&&m.getSourceSessionId().equals("s-1")));extractor.extractExplicitStatement("admin","s-1","我希望 API_KEY: TEST_SECRET_VALUE 被记住");verifyNoMoreInteractions(memories);}
    @Test void autoConfirmsOnlyExplicitLowRiskProjectFacts(){MemoryService memories=mock(MemoryService.class);when(memories.list(anyString(),isNull(),anyString(),anyInt())).thenReturn(List.of());ConversationMemoryExtractionService extractor=new ConversationMemoryExtractionService(memories);extractor.extractExplicitStatement("admin","s-2","本项目使用 Java 17 并采用 Maven 验证命令");verify(memories).create(argThat((AgentMemoryEntity m)->m.getMemoryType().equals("PROJECT_FACT")&&m.isConfirmed()&&m.getConfidence()>=.9));}
}
