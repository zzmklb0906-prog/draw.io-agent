package cn.bugstack.ai.domain.agent.model.valobj;

import com.google.adk.runner.Runner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.Map;

/**
 * Ai Agent 智能体注册值对象
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/17 08:19
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    /**
     * 智能体名称
     */
    private String appName;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /**
     * 智能体执行对象
     */
    private Runner runner;

    /** Named stage runners share the same app/session services but enter a concrete business Agent directly. */
    private Map<String, Runner> stageRunners;

    public Runner runnerFor(String stageAgentName) {
        if (stageAgentName == null || stageAgentName.isBlank() || stageRunners == null) return runner;
        return stageRunners.getOrDefault(stageAgentName, runner);
    }

}
