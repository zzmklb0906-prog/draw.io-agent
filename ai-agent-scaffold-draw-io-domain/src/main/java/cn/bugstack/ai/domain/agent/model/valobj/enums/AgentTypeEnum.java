package cn.bugstack.ai.domain.agent.model.valobj.enums;

import lombok.Getter;

@Getter
public enum AgentTypeEnum {

    Loop("循环执行", "loop", "loopAgentNode"),
    Parallel("并行执行", "parallel", "parallelAgentNode"),
    Sequential("串行执行", "sequential", "sequentialAgentNode"),

    ;

    private String name;
    private String type;
    private String node;

    AgentTypeEnum(String name, String type, String node) {
        this.name = name;
        this.type = type;
        this.node = node;
    }

    public static AgentTypeEnum formType(String type) {
        if (type == null) {
            return null;
        }

        for (AgentTypeEnum value : values()) {
            if (value.getType().equalsIgnoreCase(type)) {
                return value;
            }
        }

        return null;
    }

}
