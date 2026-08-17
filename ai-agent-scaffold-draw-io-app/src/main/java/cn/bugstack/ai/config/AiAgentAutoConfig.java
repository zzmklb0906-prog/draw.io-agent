package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;
import java.util.ArrayList;

@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IArmoryService armoryService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            log.info("Ai Agent 智能体装配，共 {} 个配置: {}", aiAgentAutoConfigProperties.getTables().size(),
                    aiAgentAutoConfigProperties.getTables().entrySet().stream().map(entry -> {
                        var value=entry.getValue();return entry.getKey()+"(agentId="+(value.getAgent()==null?"":value.getAgent().getAgentId())+")";
                    }).toList());

            armoryService.acceptArmoryAgents(new ArrayList<>(aiAgentAutoConfigProperties.getTables().values()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
