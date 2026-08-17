package cn.bugstack.ai.test.app;

import cn.bugstack.ai.Application;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.workflow.WorkflowCheckpointService;
import cn.bugstack.ai.domain.agent.service.orchestration.DynamicSubagentService;
import cn.bugstack.ai.domain.eval.adapter.IAgentEvalRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeObservationRepository;
import cn.bugstack.ai.domain.conversation.adapter.IConversationRepository;
import cn.bugstack.ai.domain.artifact.adapter.IArtifactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.lazy-initialization=false", "ai.agent.chat.streaming-enabled=false"})
class DynamicCapabilityAssemblyTest {
    @Autowired CapabilityRegistryService registry;
    @Autowired DynamicSubagentService dynamicSubagentService;
    @Autowired IAgentEvalRepository evalRepository;
    @Autowired IRuntimeObservationRepository observations;
    @Autowired IConversationRepository conversations;
    @Autowired IArtifactRepository artifacts;
    @Autowired DefaultArmoryFactory armoryFactory;
    @Autowired WorkflowCheckpointService checkpointService;

    @Test
    void shouldAssembleIndependentCheckpointStageRunners() {
        var drawAgent = armoryFactory.getAiAgentRegisterVO("300000");
        assertTrue(drawAgent.getStageRunners().containsKey("agent_analyst"));
        assertTrue(drawAgent.getStageRunners().containsKey("agent_drawer"));
        assertFalse(drawAgent.runnerFor("agent_analyst") == drawAgent.runnerFor("agent_drawer"),
                "Approval must resume at Drawer instead of replaying the sequential workflow");
    }

    @Test
    @Transactional
    void shouldGroupCheckpointInvocationsIntoOneWorkflowTask() {
        String session="checkpoint-task-"+java.util.UUID.randomUUID();
        conversations.create("admin","300000",session,"Checkpoint grouping test");
        var checkpoint=checkpointService.start("300000","admin",session,"draw a flowchart");
        String analysisInvocation="analysis-"+java.util.UUID.randomUUID();
        String drawingInvocation="drawing-"+java.util.UUID.randomUUID();
        observations.invocationStarted(analysisInvocation,session,"admin","agent_analyst",1000);
        observations.invocationCompleted(analysisInvocation,"SUCCESS",1100,100,10,5,"");
        observations.workflowState(session,checkpoint.getCheckpointId(),WorkflowCheckpointService.WAITING_APPROVAL);
        observations.invocationStarted(drawingInvocation,session,"admin","agent_drawer",1200);
        var invocations=observations.listBySession("admin",session);
        assertTrue(invocations.size()==2);
        assertTrue(String.valueOf(invocations.get(0).get("taskId")).equals(String.valueOf(invocations.get(1).get("taskId"))),
                "All physical invocations of one Checkpoint must share one Workflow task");
    }

    @Test
    void shouldRegisterConfiguredSkillCapabilitiesAtStartup() {
        assertTrue(dynamicSubagentService.templates().stream().anyMatch(item -> item.get("templateKey").equals("researcher")),
                "Runtime Subagent templates should be loaded from PostgreSQL");
        assertTrue(registry.size() >= 2, "Draw.io and PPT skills should be registered in the runtime capability registry");
        var drawio = registry.search("inv-draw", "admin", "agent_drawer", "流程图 drawio", java.util.List.of("SKILL"), 8);
        assertTrue(drawio.capabilities().stream().anyMatch(item -> item.capability().name().equals("drawio")));
        assertFalse(drawio.capabilities().stream().anyMatch(item -> item.capability().name().equals("ppt")));
        String drawioId = drawio.capabilities().get(0).capability().capabilityId();
        var loaded = registry.execute(drawio.snapshotId(), drawioId, java.util.Map.of(), null).blockingGet();
        assertTrue(String.valueOf(loaded.get("instructions")).contains("图表"));

        var ppt = registry.search("inv-ppt", "admin", "agent_ppt_generator", "PPT 布局", java.util.List.of("SKILL"), 8);
        assertTrue(ppt.capabilities().stream().anyMatch(item -> item.capability().name().equals("ppt")));
        assertFalse(ppt.capabilities().stream().anyMatch(item -> item.capability().name().equals("drawio")));
    }

    @Test
    @Transactional
    void shouldPersistAndAggregateEvalRunEvenWhenInvocationIsUnavailable() {
        String owner="eval-test-user";
        String dataset=evalRepository.createDataset(owner,"smoke","Smoke","integration");
        String caseId=evalRepository.createCase(owner,dataset,Map.of("caseKey","case-1","name","Case 1","agentId","300002","prompt","hello","expectations",Map.of(),"rubric",Map.of(),"tags",List.of()));
        String run=evalRepository.createRun(owner,dataset,"candidate",1,null,1);
        evalRepository.startRun(run);
        String caseRun=evalRepository.createCaseRun(run,caseId,1,1);
        evalRepository.startCaseRun(caseRun,"session-test");
        evalRepository.completeCaseRun(caseRun,"","ERROR","",0,false,10,0,0,0,0,Map.of(),"expected failure");
        evalRepository.finishRun(run);
        Map<String,Object> persisted=evalRepository.run(owner,run);
        assertTrue("COMPLETED".equals(persisted.get("status")));
        assertTrue(((Number)persisted.get("failedCases")).intValue()==1);
    }

    @Test
    @Transactional
    void shouldPersistHumanReadableCapabilityDecisionTrace() {
        String session="cap-session-"+java.util.UUID.randomUUID(),invocation="cap-inv-"+java.util.UUID.randomUUID();
        conversations.create("admin","300002",session,"Capability trace test");
        observations.invocationStarted(invocation,session,"admin","general_orchestrator",1000);
        Map<String,Object> candidate=Map.of("capabilityId","skill:drawio-skills:drawio","type","SKILL","group","drawio-skills","name","drawio","version",2,"riskLevel","READ_ONLY","score",9.5);
        String search=java.util.UUID.randomUUID().toString(),snapshot=java.util.UUID.randomUUID().toString(),execution=java.util.UUID.randomUUID().toString();
        observations.capabilitySearch(search,invocation,null,"general_orchestrator","call-search",snapshot,"绘制流程图",List.of("SKILL"),12,List.of(candidate),1000,1012,12,"SUCCESS","");
        observations.toolStarted(invocation,null,"general_orchestrator","call-execute","execute_capability",1015,"{\"capabilityId\":\"skill:drawio-skills:drawio\"}");
        observations.capabilityExecutionStarted(execution,invocation,null,"general_orchestrator","call-execute",snapshot,"EXECUTE",Map.of("capabilityId","skill:drawio-skills:drawio","capabilityType","SKILL","capabilityGroup","drawio-skills","capabilityName","drawio","capabilityVersion",2,"riskLevel","READ_ONLY"),"references/flowchart.md",Map.of("resourcePath","references/flowchart.md"),1020);
        observations.capabilityExecutionCompleted(execution,"SUCCESS",1030,10,"loaded",6,"abc",0,"");
        observations.toolCompleted(invocation,"call-execute","SUCCESS",1040,25,"x".repeat(9000),0,"");
        var searches=observations.capabilitySearches("admin",invocation);var executions=observations.capabilityExecutions("admin",invocation);
        assertTrue(searches.size()==1&&((List<?>)searches.get(0).get("candidates")).size()==1);
        assertTrue(Boolean.TRUE.equals(((Map<?,?>)((List<?>)searches.get(0).get("candidates")).get(0)).get("selected")));
        assertTrue(executions.size()==1&&"drawio".equals(executions.get(0).get("name"))&&"references/flowchart.md".equals(executions.get(0).get("resourcePath")));
        assertTrue(!String.valueOf(executions.get(0).get("artifactId")).isBlank(),"Large broker results should be stored as an Artifact reference");
        assertTrue(observations.waterfall("admin",invocation).stream().anyMatch(item->"CAPABILITY".equals(item.get("type"))));
    }

    @Test
    @Transactional
    void shouldAggregateChildUsageIntoRootAgentRun() {
        String session="aggregate-session-"+java.util.UUID.randomUUID(),invocation="aggregate-inv-"+java.util.UUID.randomUUID();
        String root=java.util.UUID.randomUUID().toString(),child=java.util.UUID.randomUUID().toString();
        conversations.create("admin","300000",session,"Agent aggregate test");
        observations.invocationStarted(invocation,session,"admin","sequential_draw_process",1000);
        observations.agentStarted(invocation,root,null,"sequential_draw_process","root",1000);
        observations.agentStarted(invocation,child,root,"agent_drawer","root/agent_drawer",1010);
        observations.modelStarted(invocation,child,"agent_drawer",1020,90);
        observations.modelCompleted(invocation,child,"agent_drawer",1040,20,100,50,"SUCCESS","");
        observations.toolStarted(invocation,child,"agent_drawer","aggregate-tool","execute_capability",1042,"{}");
        observations.toolCompleted(invocation,"aggregate-tool","SUCCESS",1050,8,"ok",0,"");
        observations.agentCompleted(invocation,child,"agent_drawer",1060,50,1,20,100,50);
        observations.agentCompleted(invocation,root,"sequential_draw_process",1070,70,0,0,0,0);
        Map<String,Object> rootRun=observations.agentRuns("admin",invocation).stream().filter(item->"ROOT".equals(item.get("role"))).findFirst().orElseThrow();
        assertTrue(((Number)rootRun.get("modelCallCount")).intValue()==1);
        assertTrue(((Number)rootRun.get("toolCallCount")).intValue()==1);
        assertTrue(((Number)rootRun.get("inputTokens")).longValue()==100);
        assertTrue(((Number)rootRun.get("outputTokens")).longValue()==50);
    }

    @Test
    @Transactional
    void shouldPersistArtifactWithCurrentSchemaAndResolveStaleRunningConversation() {
        String session="conversation-state-"+java.util.UUID.randomUUID(),invocation="conversation-inv-"+java.util.UUID.randomUUID();
        var conversation=conversations.create("admin","300000",session,"State reconciliation test");
        observations.invocationStarted(invocation,session,"admin","sequential_draw_process",1000);
        conversations.updateStatus("admin",conversation.id(),"RUNNING",invocation);
        observations.invocationCompleted(invocation,"SUCCESS",1200,200,0,0,"");
        java.util.UUID artifactId=artifacts.save(conversation.id(),invocation,"DRAWIO","diagram","application/xml","<mxGraphModel/>","{}");
        assertTrue(artifactId!=null);
        assertTrue("COMPLETED".equals(conversations.get("admin",conversation.id()).orElseThrow().status()));
        Map<String,Object> scopedSummary=observations.summary("admin",session);
        assertTrue(((Number)scopedSummary.get("total")).longValue()==1);
        assertTrue(((Number)scopedSummary.get("success")).longValue()==1);
    }
}
