package cn.bugstack.ai.domain.agent.service.agent.routing;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule-based heuristic implementation of {@link AgentRequirementAnalyzer}.
 *
 * <p><strong>Architectural Guardrail:</strong>
 * Only outputs abstract {@link AgentCapability} and {@link TaskType} sets.
 * Does NOT access {@link AgentRegistry} or mention concrete Agent IDs.</p>
 */
@Component
public class RuleBasedAgentRequirementAnalyzer implements AgentRequirementAnalyzer {

    private static final List<String> DRAWING_SIGNALS = List.of(
            "画一个", "画个", "画出", "绘制", "生成图", "架构图", "时序图", "流程图", "拓扑图", "泳道图", "er图", "uml", "drawio", "diagram", "flowchart"
    );

    private static final List<String> CODING_SIGNALS = List.of(
            "编写代码", "写一段代码", "写个函数", "写一个类", "实现类", "实现接口", "重构代码", "写单元测试", "fix bug", "java", "python", "golang", "typescript", "code"
    );

    private static final List<String> DOCUMENT_SIGNALS = List.of(
            "总结文档", "阅读这篇", "分析文档", "提取要点", "整理需求", "格式化文本", "排版", "论文阅读", "阅读报告", "markdown排版"
    );

    private static final List<String> DATA_ANALYSIS_SIGNALS = List.of(
            "统计数据", "分析指标", "计算吞吐量", "统计活跃", "生成报表", "sql查询", "数据洞察", "指标趋势"
    );

    @Override
    public AgentRequirement analyze(String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return AgentRequirement.generalChat(prompt);
        }

        String lower = prompt.toLowerCase();
        Set<AgentCapability> capabilities = new HashSet<>();
        Set<TaskType> taskTypes = new HashSet<>();
        boolean requiresTools = false;
        boolean requiresPlanning = false;

        // Visual / Diagramming Capability
        if (containsAny(lower, DRAWING_SIGNALS)) {
            capabilities.add(AgentCapability.DRAWING);
            capabilities.add(AgentCapability.TOOL_ORCHESTRATION);
            taskTypes.add(TaskType.DRAWIO_GENERATION);
            requiresTools = true;
        }

        // Code Generation Capability
        if (containsAny(lower, CODING_SIGNALS)) {
            capabilities.add(AgentCapability.CODE_GENERATION);
            taskTypes.add(TaskType.CODE_GENERATION);
            requiresPlanning = true;
        }

        // Document Analysis Capability
        if (containsAny(lower, DOCUMENT_SIGNALS)) {
            capabilities.add(AgentCapability.DOCUMENT_ANALYSIS);
            taskTypes.add(TaskType.ANALYZE);
        }

        // Data Analysis Capability
        if (containsAny(lower, DATA_ANALYSIS_SIGNALS)) {
            capabilities.add(AgentCapability.DATA_ANALYSIS);
            taskTypes.add(TaskType.ANALYZE);
            requiresTools = true;
        }

        // Fallback to General Chat if no specific capability triggered
        if (capabilities.isEmpty()) {
            capabilities.add(AgentCapability.GENERAL_CHAT);
            taskTypes.add(TaskType.GENERAL_CHAT);
        }

        return new AgentRequirement(
                taskTypes,
                capabilities,
                requiresTools,
                requiresPlanning,
                prompt
        );
    }

    private boolean containsAny(String text, List<String> patterns) {
        for (String p : patterns) {
            if (text.contains(p)) return true;
        }
        return false;
    }
}
