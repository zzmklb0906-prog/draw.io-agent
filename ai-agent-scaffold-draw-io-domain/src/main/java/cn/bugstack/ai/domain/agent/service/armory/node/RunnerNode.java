package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * 执行节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/29 16:09
 */
@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {

    @Resource
    private BaseSessionService sessionService;

    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String appName = aiAgentConfigTableVO.getAppName();
        AiAgentConfigTableVO.Agent agent = aiAgentConfigTableVO.getAgent();
        String agentId = agent.getAgentId();
        String agentName = agent.getAgentName();
        String agentDesc = agent.getAgentDesc();

        Runner runner = getRunner(dynamicContext, aiAgentConfigTableVO, appName);
        Map<String,Runner> stageRunners=new LinkedHashMap<>();
        for(Map.Entry<String,BaseAgent> entry:dynamicContext.getAgentGroup().entrySet()){
            stageRunners.put(entry.getKey(),getRunner(dynamicContext,aiAgentConfigTableVO,appName,entry.getValue(),false));
        }

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .stageRunners(stageRunners)
                .build();

        // 注册到 Spring 容器
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return aiAgentRegisterVO;
    }

    private Runner getRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName) {
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        String agentName = runnerConfig.getAgentName();
        if (StringUtils.isBlank(agentName)) {
            log.error("runner.agentName is null");
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);
        return getRunner(dynamicContext,aiAgentConfigTableVO,appName,baseAgent,true);
    }

    private Runner getRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName,BaseAgent baseAgent,boolean compactionEnabled) {
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        List<BasePlugin> plugins;
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        if (null != pluginNameList && !pluginNameList.isEmpty()) {
            plugins = new ArrayList<>();
            for (String pluginName : pluginNameList) {
                BasePlugin plugin = getBean(pluginName);
                plugins.add(plugin);
            }
        } else {
            plugins = ImmutableList.of();
        }

        App.Builder appBuilder = App.builder().name(appName).rootAgent(baseAgent).plugins(plugins)
                .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build());
        if (compactionEnabled && !Boolean.FALSE.equals(runnerConfig.getCompactionEnabled())) {
            EventsCompactionConfig compaction = EventsCompactionConfig.builder()
                    .tokenThreshold(runnerConfig.getCompactionTokenThreshold())
                    .eventRetentionSize(runnerConfig.getCompactionEventRetentionSize())
                    .summarizer(new LlmEventSummarizer(dynamicContext.getLlm()))
                    .build();
            appBuilder.eventsCompactionConfig(compaction);
            log.info("Runner '{}' 已开启 ADK LLM 事件压缩: threshold={}, retention={}", appName,
                    runnerConfig.getCompactionTokenThreshold(), runnerConfig.getCompactionEventRetentionSize());
        }
        return Runner.builder()
                .app(appBuilder.build())
                .artifactService(new InMemoryArtifactService())
                .sessionService(sessionService)
                .memoryService(new InMemoryMemoryService())
                .build();
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }


}
