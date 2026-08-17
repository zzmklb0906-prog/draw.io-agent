package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import cn.bugstack.ai.domain.agent.service.llm.OpenAiCompatibleLlm;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import com.google.adk.tools.BaseTool;
import com.google.adk.models.BaseLlm;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.SseServerParameters;
import com.google.adk.tools.mcp.StdioServerParameters;
import com.google.adk.tools.BaseToolset;
import cn.bugstack.ai.domain.agent.service.armory.matter.tool.GovernedToolset;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityBrokerToolset;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import cn.bugstack.ai.domain.agent.service.orchestration.DynamicSubagentToolset;
import cn.bugstack.ai.domain.idempotency.service.IdempotencyService;
import cn.bugstack.ai.domain.agent.service.monitor.InvocationVersionCatalog;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Value("${ai.agent.tool-governance.timeout-ms:120000}")
    private long toolTimeoutMs;
    @Value("${ai.agent.tool-governance.failure-threshold:3}")
    private int toolFailureThreshold;
    @Value("${ai.agent.tool-governance.cooldown-ms:60000}")
    private long toolCooldownMs;
    @Value("${ai.agent.tool-governance.max-retries:2}")
    private int toolMaxRetries;
    @Value("${ai.agent.tool-governance.retry-backoff-ms:500}")
    private long toolRetryBackoffMs;
    @Value("${ai.agent.tool-governance.max-concurrency:8}")
    private int toolMaxConcurrency;
    @Value("${ai.agent.tool-governance.max-result-bytes:131072}")
    private int toolMaxResultBytes;

    @Resource
    private AgentNode agentNode;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Resource
    private LightweightMonitorService lightweightMonitorService;

    @Resource
    private CapabilityBrokerToolset capabilityBrokerToolset;
    @Resource
    private CapabilityRegistryService capabilityRegistryService;
    @Resource
    private DynamicSubagentToolset dynamicSubagentToolset;
    @Resource
    private IdempotencyService idempotencyService;
    @Resource
    private InvocationVersionCatalog invocationVersionCatalog;


    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 获取配置对象
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        invocationVersionCatalog.register(aiAgentConfigTableVO.getAppName(),aiAgentConfigTableVO);
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();

        // 工具按组注册，由各 LlmAgent 显式选择，不再污染共享 ChatModel。
        Map<String, List<Object>> toolGroups = new LinkedHashMap<>();

        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                String groupName = resolveMcpGroupName(toolMcp);
                Object rawToolset = createMcpToolset(toolMcp);
                if (rawToolset instanceof BaseToolset baseToolset) capabilityRegistryService.registerToolset(groupName, "MCP_TOOL", toolMcp.getRiskLevel(), baseToolset);
                toolGroups.put(groupName, List.of(govern(rawToolset)));
            }
        }

        // 构建skills服务
        if (null != toolSkillsList && !toolSkillsList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                Object toolset = toolSkillsCreateService.buildToolset(toolSkills);
                toolGroups.put(toolSkills.getName(), List.of(govern(toolset)));
            }
        }
        toolGroups.put("capability-broker", List.of(govern(capabilityBrokerToolset)));
        toolGroups.put("dynamic-subagent", List.of(govern(dynamicSubagentToolset)));
        lightweightMonitorService.registerTool("search_capabilities");
        lightweightMonitorService.registerTool("load_capability");
        lightweightMonitorService.registerTool("execute_capability");
        lightweightMonitorService.registerTool("list_subagent_templates");
        lightweightMonitorService.registerTool("spawn_subagent");
        lightweightMonitorService.registerTool("await_subagent");

        // 直接创建 OpenAI-compatible 流式模型，再包装为 ADK BaseLlm。
        // DeepSeek 可配置 base-url=https://api.deepseek.com 且 completions-path=chat/completions，
        // 或 base-url=https://api.deepseek.com 且 completions-path=/v1/chat/completions。
        BaseLlm llm = new OpenAiCompatibleLlm(
                chatModelConfig.getModel(), aiApiConfig.getBaseUrl(),
                aiApiConfig.getApiKey(), aiApiConfig.getCompletionsPath());

        dynamicContext.setLlm(llm);
        dynamicContext.setToolGroups(toolGroups);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

    private String resolveMcpGroupName(AiAgentConfigTableVO.Module.ChatModel.ToolMcp config) {
        if (config.getName() != null && !config.getName().isBlank()) return config.getName();
        if (config.getSse() != null) return config.getSse().getName();
        if (config.getStdio() != null) return config.getStdio().getName();
        if (config.getLocal() != null) return config.getLocal().getName();
        throw new IllegalArgumentException("MCP tool group must have a name");
    }

    private Object createMcpToolset(AiAgentConfigTableVO.Module.ChatModel.ToolMcp config) throws Exception {
        List<String> included=config.getIncludedTools()==null?List.of():config.getIncludedTools().stream().filter(v->v!=null&&!v.isBlank()).distinct().toList();
        if(included.size()>64)throw new IllegalArgumentException("单个 MCP 工具组最多暴露 64 个 Tool，请按业务域拆组并使用 included-tools 白名单");
        if(included.isEmpty()&&!Boolean.TRUE.equals(config.getAllowAllTools()))throw new IllegalArgumentException("MCP 工具组必须配置 included-tools；如确需暴露全部工具，请显式设置 allow-all-tools: true");
        if (config.getSse() != null) {
            var sse = config.getSse();
            SseServerParameters parameters = SseServerParameters.builder()
                    .url(sse.getBaseUri())
                    .sseEndpoint(sse.getSseEndpoint())
                    .timeout(Duration.ofMillis(sse.getRequestTimeout()))
                    .sseReadTimeout(Duration.ofMillis(sse.getRequestTimeout()))
                    .build();
            return included.isEmpty()?new McpToolset(parameters):new McpToolset(parameters,new ObjectMapper(),included);
        }
        if (config.getStdio() != null) {
            var stdio = config.getStdio();
            var server = stdio.getServerParameters();
            StdioServerParameters parameters = StdioServerParameters.builder()
                    .command(server.getCommand())
                    .args(server.getArgs() == null ? List.of() : server.getArgs())
                    .env(server.getEnv() == null ? Map.of() : server.getEnv())
                    .build();
            return included.isEmpty()?new McpToolset(parameters.toServerParameters()):new McpToolset(parameters.toServerParameters(),new ObjectMapper(),included);
        }
        throw new IllegalArgumentException("local MCP 旧配置已移除；请直接注册 ADK FunctionTool，或改用 SSE/stdio McpToolset");
    }

    private Object govern(Object tool) {
        return tool instanceof BaseToolset toolset
                ? new GovernedToolset(toolset, toolTimeoutMs, toolFailureThreshold, toolCooldownMs,
                    toolMaxRetries,toolRetryBackoffMs,toolMaxConcurrency,toolMaxResultBytes,idempotencyService,lightweightMonitorService)
                : tool;
    }

}
