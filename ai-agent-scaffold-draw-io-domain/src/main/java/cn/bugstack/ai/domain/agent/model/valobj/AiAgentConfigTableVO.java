package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Ai Agent 智能体配置表值对象
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/11/29 10:54
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

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

    }

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/v1/chat/completions";
            private String embeddingsPath = "/v1/embeddings";

        }

        @Data
        public static class ChatModel {

            private String model;

            private List<ToolMcp> toolMcpList;

            private List<ToolSkills> toolSkillsList;

            @Data
            public static class ToolMcp {

                /** Agent 引用的工具组名称。 */
                private String name;

                private SSEServerParameters sse;

                private StdioServerParameters stdio;

                private LocalParameters local;

                /** MCP Server 很大时必须显式选择当前工具组允许暴露的工具。 */
                private List<String> includedTools;

                /** 默认禁止无边界地暴露 MCP Server 的全部工具。 */
                private Boolean allowAllTools = false;

                /** READ_ONLY 可自动执行；其他级别要求 ADK Tool Confirmation。 */
                private String riskLevel = "REQUIRES_APPROVAL";

                @Data
                public static class SSEServerParameters {
                    private String name;
                    private String baseUri;
                    private String sseEndpoint;
                    private Integer requestTimeout = 3000;

                }

                @Data
                public static class StdioServerParameters {
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        private String command;
                        private List<String> args;
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

            }

            @Data
            public static class ToolSkills {

                /** Agent 引用的工具组名称。 */
                private String name = "skills";

                /**
                 * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
                 */
                private String type = "directory";

                /**
                 * 路径；
                 */
                private String path;

                /** 当前工具组允许出现在 Skill catalog 中的 Skill；空列表表示全部。 */
                private List<String> includedSkills;

            }

        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;

            /** 仅把指定工具组装配给当前 Agent。 */
            private List<String> tools;

            /** 通过 Capability Broker 运行时检索的工具组；支持 "*"。 */
            private List<String> capabilityGroups;

        }

        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
            /** ADK 原生事件压缩；默认开启。 */
            private Boolean compactionEnabled = true;
            /** 约为 128K 上下文的 70%。 */
            private Integer compactionTokenThreshold = 89600;
            /** 压缩后保留最近事件数。 */
            private Integer compactionEventRetentionSize = 12;
        }
    }

}
