package cn.bugstack.ai.domain.agent.service.llm.routing.requirement;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic Task Type Detector.
 *
 * <p>Uses high-confidence keyword signals and heuristic rule precedence to determine
 * the coarse-grained {@link TaskType}. Handles simple negation/override contexts
 * (e.g. lightweight title modification with architectural keywords) to prioritize action intent.</p>
 */
@Component
public class TaskTypeDetector {

    public record DetectionResult(TaskType taskType, List<String> signals, List<String> matchedPatterns) {}

    public DetectionResult detect(String latestUserText) {
        if (StringUtils.isBlank(latestUserText)) {
            return new DetectionResult(TaskType.GENERAL_CHAT, List.of("empty-message"), List.of("default-chat"));
        }

        String lower = latestUserText.trim().toLowerCase();
        int length = lower.length();
        List<String> signals = new ArrayList<>();
        List<String> matchedPatterns = new ArrayList<>();

        // 1. High-confidence SIMPLE_EDIT precedence (negation / action-intent override)
        // e.g. "把这个架构图的标题改成系统架构", "不需要分析架构，只修改节点名称", "rename title to login"
        boolean hasNegation = containsAny(lower, "不需要", "不用", "无需", "不需", "别", "no need", "don't", "do not");
        boolean hasComplexWord = containsAny(lower, "分析", "架构", "设计", "排查", "推导", "analyze", "analysis", "architecture");
        boolean hasEditAction = containsAny(lower,
                "修改标题", "改标题", "换标题", "改名", "修改名称", "修改节点", "改节点",
                "只修改", "只改", "替换标题", "标题改成", "名字改成", "名称改成", "更新标题", "调整标题",
                "改成", "换成", "重命名",
                "rename", "change title", "edit title", "update title", "rename title", "modify title",
                "change name", "rename node", "just rename", "just change", "only change", "just edit", "only edit");

        if (length < 150) {
            // Negated complex nouns in simple edit: e.g. "不需要分析架构，只修改节点名称"
            if (hasNegation && hasComplexWord && (hasEditAction || containsAny(lower, "修改", "改", "替换", "更新", "rename", "change", "edit"))) {
                signals.add("negation-override-edit");
                matchedPatterns.add("negated-complex-noun-simple-edit");
                return new DetectionResult(TaskType.SIMPLE_EDIT, signals, matchedPatterns);
            }
            // Direct high-confidence edit action intent
            if (hasEditAction) {
                signals.add("edit-action");
                matchedPatterns.add("high-confidence-simple-edit");
                return new DetectionResult(TaskType.SIMPLE_EDIT, signals, matchedPatterns);
            }
        }

        // 2. DRAWIO_REVIEW: XML / mxGraph checking and repair
        boolean hasReviewVerb = containsAny(lower, "检查", "修复", "校验", "审核", "review", "check", "repair", "validate");
        boolean hasGraphObject = containsAny(lower, "xml", "mxgraph", "图", "节点", "连线", "mxcell", "mxgraphmodel", "diagram");
        if ((hasReviewVerb && hasGraphObject) || containsAny(lower, "mxgraphmodel", "mxcell", "语法校验", "xml validation")) {
            signals.add("drawio-review");
            matchedPatterns.add("drawio-xml-inspection");
            return new DetectionResult(TaskType.DRAWIO_REVIEW, signals, matchedPatterns);
        }

        // 3. DRAWIO_GENERATION: Flowchart, Sequence diagram, Architecture diagram generation
        if (containsAny(lower, "流程图", "时序图", "拓扑图", "状态机图", "架构图", "draw.io", "drawio", "mxgraph", "画一个", "画一张", "绘制图", "生成图",
                "flowchart", "sequence diagram", "topology diagram", "draw a", "draw diagram", "generate diagram")) {
            signals.add("drawio-generation");
            matchedPatterns.add("diagram-generation-intent");
            return new DetectionResult(TaskType.DRAWIO_GENERATION, signals, matchedPatterns);
        }

        // 4. DIAGNOSE / CODE_ANALYSIS
        if (containsAny(lower, "死锁", "并发问题", "排查", "bug", "根因", "内存泄漏", "oom", "异常堆栈", "性能瓶颈",
                "deadlock", "concurrency issue", "root cause", "memory leak", "diagnose", "troubleshoot")) {
            signals.add("diagnose-issue");
            matchedPatterns.add("diagnostic-intent");
            return new DetectionResult(TaskType.DIAGNOSE, signals, matchedPatterns);
        }

        // 5. CODE_GENERATION / CODE_ANALYSIS
        if (containsAny(lower, "写代码", "生成代码", "编写程序", "java", "python", "javascript", "golang", "重构代码", "实现接口",
                "write code", "generate code", "coding", "implement", "refactor code")) {
            signals.add("coding-request");
            matchedPatterns.add("code-generation-intent");
            return new DetectionResult(TaskType.CODE_GENERATION, signals, matchedPatterns);
        }

        // 6. SUMMARIZE / EXTRACT / FORMAT
        if (containsAny(lower, "总结", "概括", "归纳", "摘要", "提炼", "summarize", "summary", "tldr")) {
            signals.add("summarize");
            matchedPatterns.add("summarization-intent");
            return new DetectionResult(TaskType.SUMMARIZE, signals, matchedPatterns);
        }
        if (containsAny(lower, "格式化", "排版", "整理格式", "json 格式", "转换格式", "format", "beautify")) {
            signals.add("format");
            matchedPatterns.add("formatting-intent");
            return new DetectionResult(TaskType.FORMAT, signals, matchedPatterns);
        }
        if (containsAny(lower, "提取", "抽取", "抓取关键信息", "extract")) {
            signals.add("extract");
            matchedPatterns.add("extraction-intent");
            return new DetectionResult(TaskType.EXTRACT, signals, matchedPatterns);
        }

        // 7. Complex ANALYZE / PLAN
        if (containsAny(lower, "分析", "架构", "设计方案", "系统设计", "规划", "推导", "为什么", "原理解析",
                "analyze", "analysis", "system design", "architecture", "plan")) {
            signals.add("analysis");
            matchedPatterns.add("deep-analysis-intent");
            return new DetectionResult(TaskType.ANALYZE, signals, matchedPatterns);
        }

        // 8. Default: General Chat
        signals.add("general-text");
        matchedPatterns.add("general-fallback");
        return new DetectionResult(TaskType.GENERAL_CHAT, signals, matchedPatterns);
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }
}
