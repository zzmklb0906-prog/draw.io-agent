package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;

import jakarta.annotation.Resource;
import com.google.adk.tools.BaseTool;
import java.util.ArrayList;
import java.util.List;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import cn.bugstack.ai.domain.agent.service.orchestration.DynamicSubagentService;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private LightweightMonitorService lightweightMonitorService;

    @Resource
    private CapabilityRegistryService capabilityRegistryService;
    @Resource
    private DynamicSubagentService dynamicSubagentService;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        BaseLlm llm = dynamicContext.getLlm();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            List<Object> tools = new ArrayList<>();
            if (agentConfig.getTools() != null) {
                for (String groupName : agentConfig.getTools()) {
                    List<Object> group = dynamicContext.getToolGroups().get(groupName);
                    if (group == null) {
                        throw new IllegalArgumentException("Agent '" + agentConfig.getName()
                                + "' references unknown tool group '" + groupName + "'");
                    }
                    tools.addAll(group);
                }
            }
            if (agentConfig.getCapabilityGroups() != null && !agentConfig.getCapabilityGroups().isEmpty()) {
                capabilityRegistryService.allowAgentGroups(agentConfig.getName(), agentConfig.getCapabilityGroups());
                tools.addAll(dynamicContext.getToolGroups().get("capability-broker"));
            }
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(llm)
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .tools(tools)
                    .build();

            if(agentConfig.getTools()!=null&&agentConfig.getTools().contains("dynamic-subagent")){
                // Children inherit only the capability broker and the constrained Subagent control plane.
                // Recursion is bounded by DynamicSubagentService depth/task/concurrency/token budgets.
                List<Object> inherited=new ArrayList<>(dynamicContext.getToolGroups().getOrDefault("capability-broker",List.of()));
                inherited.addAll(dynamicContext.getToolGroups().getOrDefault("dynamic-subagent",List.of()));
                dynamicSubagentService.registerRuntime(agentConfig.getName(),llm,inherited);
            }

            log.info("Agent '{}' 已装配静态工具组 {}、动态能力域 {}，共 {} 个 ADK Toolset/Tool",
                    agentConfig.getName(), agentConfig.getTools(), agentConfig.getCapabilityGroups(), tools.size());

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
