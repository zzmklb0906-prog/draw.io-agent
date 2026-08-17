package cn.bugstack.ai.domain.eval.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentEvalServiceTest {

    private final AgentEvalService service = new AgentEvalService(null, null, null, null);

    @Test
    void shouldPassContentTrajectoryAndBudgetContract() {
        Map<String,Object> definition=Map.of(
                "expectations",Map.of("requiredText",List.of("mxGraphModel"),"requiredTools",List.of("execute_capability"),"maxTokens",5000,"passScore",75),
                "rubric",Map.of("contentWeight",50,"trajectoryWeight",40,"efficiencyWeight",10));
        Map<String,Object> trace=Map.of("tools",List.of(Map.of("toolName","execute_capability")),"models",List.of(Map.of()),"totalTokens",1200L,"durationMs",100L);
        Map<String,Object> grade=service.gradeSnapshotForTest(definition,"<mxGraphModel/>",trace);
        assertEquals(Boolean.TRUE,grade.get("passed"));
        assertEquals(100.0,grade.get("score"));
    }

    @Test
    void forbiddenToolMustFailHardGateAndUnexpectedToolLowersPrecision() {
        Map<String,Object> definition=Map.of(
                "expectations",Map.of("forbiddenTools",List.of("delete_file"),"requiredTools",List.of("read_file"),"passScore",1),
                "rubric",Map.of("contentWeight",0,"trajectoryWeight",100,"efficiencyWeight",0));
        Map<String,Object> trace=Map.of("tools",List.of(Map.of("toolName","read_file"),Map.of("toolName","delete_file"),Map.of("toolName","unnecessary_search")),"models",List.of());
        Map<String,Object> grade=service.gradeSnapshotForTest(definition,"ok",trace);
        assertEquals(Boolean.FALSE,grade.get("passed"));
        @SuppressWarnings("unchecked") Map<String,Object> breakdown=(Map<String,Object>)grade.get("breakdown");
        assertEquals(0.33,breakdown.get("toolPrecision"));
    }

    @Test
    void shouldGradeExactCapabilityIdentityAndResourceInsteadOfEventText() {
        Map<String,Object> contract=Map.of("type","SKILL","name","drawio","action","EXECUTE");
        Map<String,Object> resource=Map.of("type","SKILL","name","drawio","action","EXECUTE","resourcePath","references/flowchart.md");
        Map<String,Object> definition=Map.of("expectations",Map.of("requiredCapabilities",List.of(contract),"requiredResources",List.of(resource),"passScore",100),"rubric",Map.of("contentWeight",0,"trajectoryWeight",100,"efficiencyWeight",0));
        Map<String,Object> execution=Map.of("type","SKILL","name","drawio","action","EXECUTE","resourcePath","references/flowchart.md","status","SUCCESS");
        Map<String,Object> grade=service.gradeSnapshotForTest(definition,"",Map.of("tools",List.of(),"models",List.of(),"capabilityExecutions",List.of(execution)));
        assertEquals(Boolean.TRUE,grade.get("passed"));
        assertEquals(100.0,grade.get("score"));
    }
}
