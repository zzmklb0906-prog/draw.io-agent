package cn.bugstack.ai.domain.eval.service;

import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeObservationRepository;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.conversation.adapter.IConversationRepository;
import cn.bugstack.ai.domain.eval.adapter.IAgentEvalRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AgentEvalService {
    private final IAgentEvalRepository eval;
    private final IRuntimeObservationRepository observations;
    private final IChatService chat;
    private final IConversationRepository conversations;
    private final ExecutorService executor=Executors.newFixedThreadPool(2);
    public AgentEvalService(IAgentEvalRepository eval,IRuntimeObservationRepository observations,IChatService chat,IConversationRepository conversations){this.eval=eval;this.observations=observations;this.chat=chat;this.conversations=conversations;}

    public List<Map<String,Object>> datasets(String user){return eval.datasets(user);}
    public Map<String,Object> dataset(String user,String id){Map<String,Object>d=new LinkedHashMap<>(eval.dataset(user,id));if(d.isEmpty())return d;d.put("cases",eval.cases(user,id,false));d.put("runs",eval.runs(user,id,30));return d;}
    public String createDataset(String user,String key,String name,String description){return eval.createDataset(user,key,name,description);}
    public String createCase(String user,String dataset,Map<String,Object>definition){return eval.createCase(user,dataset,definition);}
    public void updateCase(String user,String caseId,Map<String,Object>definition){eval.updateCase(user,caseId,definition);}
    public List<Map<String,Object>> runs(String user,String dataset){return eval.runs(user,dataset,100);}
    public Map<String,Object> run(String user,String id){Map<String,Object>r=new LinkedHashMap<>(eval.run(user,id));if(r.isEmpty())return r;List<Map<String,Object>> caseRuns=eval.caseRuns(user,id);caseRuns.forEach(cr->cr.put("assertions",eval.assertions(user,String.valueOf(cr.get("id")))));r.put("caseRuns",caseRuns);return r;}
    public List<Map<String,Object>> forInvocation(String user,String invocation){return eval.evaluationsForInvocation(user,invocation);}
    public void baseline(String user,String dataset,String run){eval.setBaseline(user,dataset,run);}

    public String start(String user,String datasetId,String label,int repeats,String baseline){
        List<Map<String,Object>> cases=eval.cases(user,datasetId,true);
        if(cases.isEmpty())throw new IllegalArgumentException("Dataset has no enabled cases");
        int bounded=Math.max(1,Math.min(repeats,10));String run=eval.createRun(user,datasetId,label,bounded,baseline,cases.size()*bounded);
        executor.submit(()->executeRun(user,run,cases,bounded));return run;
    }

    private void executeRun(String user,String run,List<Map<String,Object>> cases,int repeats){
        eval.startRun(run);
        try{for(Map<String,Object> c:cases)for(int repeat=1;repeat<=repeats;repeat++)executeCase(user,run,c,repeat);eval.finishRun(run);}
        catch(Throwable error){eval.failRun(run,message(error));}
    }

    private void executeCase(String user,String run,Map<String,Object> c,int repeat){
        String caseRun=eval.createCaseRun(run,str(c,"id"),number(c,"version",1),repeat);String session="",invocation="";long started=System.currentTimeMillis();
        try{
            String agent=str(c,"agentId");session=chat.createSession(agent,user);conversations.create(user,agent,session,"Eval · "+str(c,"name"));eval.startCaseRun(caseRun,session);
            List<String> outputs=chat.handleMessageForEvaluation(agent,user,session,str(c,"prompt"));String finalOutput=lastOutput(outputs);
            List<Map<String,Object>> invocations=observations.listBySession(user,session);if(invocations.isEmpty())throw new IllegalStateException("Agent execution produced no observable invocation");
            invocation=String.valueOf(invocations.get(0).get("invocationId"));Map<String,Object> trace=new LinkedHashMap<>(observations.detail(user,invocation));trace.put("capabilitySearches",observations.capabilitySearches(user,invocation));trace.put("capabilityExecutions",observations.capabilityExecutions(user,invocation));
            Grade grade=grade(c,finalOutput,trace);for(Map<String,Object>a:grade.assertions)eval.addAssertion(caseRun,a);
            long duration=number(trace,"durationMs",System.currentTimeMillis()-started),input=number(trace,"inputTokens",0),output=number(trace,"outputTokens",0);int tools=list(trace,"tools").size(),models=list(trace,"models").size();
            eval.completeCaseRun(caseRun,invocation,"COMPLETED",finalOutput,grade.score,grade.passed,duration,input,output,tools,models,grade.breakdown,"");
        }catch(Throwable error){eval.completeCaseRun(caseRun,invocation,"ERROR","",0,false,System.currentTimeMillis()-started,0,0,0,0,Map.of(),message(error));}
    }

    private Grade grade(Map<String,Object> c,String output,Map<String,Object> trace){
        Map<String,Object> expected=map(c.get("expectations")),rubric=map(c.get("rubric"));double contentWeight=dbl(rubric,"contentWeight",50),trajectoryWeight=dbl(rubric,"trajectoryWeight",40),efficiencyWeight=dbl(rubric,"efficiencyWeight",10);double totalWeight=Math.max(1,contentWeight+trajectoryWeight+efficiencyWeight);contentWeight=contentWeight/totalWeight*100;trajectoryWeight=trajectoryWeight/totalWeight*100;efficiencyWeight=efficiencyWeight/totalWeight*100;
        List<Map<String,Object>> assertions=new ArrayList<>();List<String> requiredText=strings(expected.get("requiredText")),forbiddenText=strings(expected.get("forbiddenText"));List<Map<String,Object>> tools=list(trace,"tools");List<String> actualTools=tools.stream().map(t->Objects.toString(t.get("toolName"),"")).toList();List<String> requiredTools=strings(expected.get("requiredTools")),forbiddenTools=strings(expected.get("forbiddenTools"));List<Object> requiredCapabilities=objects(expected.get("requiredCapabilities")),requiredResources=objects(expected.get("requiredResources"));List<Map<String,Object>> capabilityExecutions=list(trace,"capabilityExecutions");
        double contentScore=0;int contentChecks=Math.max(1,requiredText.size()+forbiddenText.size());for(String text:requiredText){boolean pass=output.contains(text);double max=contentWeight/contentChecks;assertions.add(assertion("CONTENT","required-text:"+text,"输出应包含 "+text,pass,false,pass?max:0,max,Map.of("text",text),Map.of("found",pass),""));contentScore+=pass?max:0;}for(String text:forbiddenText){boolean pass=!output.contains(text);double max=contentWeight/contentChecks;assertions.add(assertion("CONTENT","forbidden-text:"+text,"输出不应包含 "+text,pass,true,pass?max:0,max,Map.of("text",text),Map.of("found",!pass),""));contentScore+=pass?max:0;}if(requiredText.isEmpty()&&forbiddenText.isEmpty())contentScore=contentWeight;
        double trajectoryScore=0;int trajectoryChecks=Math.max(1,requiredTools.size()+forbiddenTools.size()+requiredCapabilities.size()+requiredResources.size());for(String tool:requiredTools){boolean pass=actualTools.contains(tool);double max=trajectoryWeight/trajectoryChecks;assertions.add(assertion("TOOL_TRAJECTORY","required-tool:"+tool,"应调用 Tool "+tool,pass,false,pass?max:0,max,Map.of("tool",tool),Map.of("tools",actualTools),""));trajectoryScore+=pass?max:0;}for(String tool:forbiddenTools){boolean pass=!actualTools.contains(tool);double max=trajectoryWeight/trajectoryChecks;assertions.add(assertion("TOOL_TRAJECTORY","forbidden-tool:"+tool,"不应调用 Tool "+tool,pass,true,pass?max:0,max,Map.of("tool",tool),Map.of("tools",actualTools),""));trajectoryScore+=pass?max:0;}for(Object contract:requiredCapabilities){boolean pass=capabilityExecutions.stream().anyMatch(execution->matchesCapability(execution,contract));double max=trajectoryWeight/trajectoryChecks;String label=capabilityLabel(contract);assertions.add(assertion("CAPABILITY_TRAJECTORY","required-capability:"+label,"应执行能力 "+label,pass,false,pass?max:0,max,contract,capabilityExecutions,""));trajectoryScore+=pass?max:0;}for(Object contract:requiredResources){boolean pass=capabilityExecutions.stream().anyMatch(execution->matchesResource(execution,contract));double max=trajectoryWeight/trajectoryChecks;String label=capabilityLabel(contract);assertions.add(assertion("CAPABILITY_RESOURCE","required-resource:"+label,"应读取能力资源 "+label,pass,false,pass?max:0,max,contract,capabilityExecutions,""));trajectoryScore+=pass?max:0;}if(requiredTools.isEmpty()&&forbiddenTools.isEmpty()&&requiredCapabilities.isEmpty()&&requiredResources.isEmpty())trajectoryScore=trajectoryWeight;
        long duration=number(trace,"durationMs",0),tokens=number(trace,"totalTokens",number(trace,"inputTokens",0)+number(trace,"outputTokens",0));int modelCalls=list(trace,"models").size(),toolCalls=tools.size();List<Budget> budgets=new ArrayList<>();addBudget(budgets,"maxDurationMs",duration,expected);addBudget(budgets,"maxTokens",tokens,expected);addBudget(budgets,"maxModelCalls",modelCalls,expected);addBudget(budgets,"maxToolCalls",toolCalls,expected);double efficiencyScore=budgets.isEmpty()?efficiencyWeight:0;for(Budget b:budgets){boolean pass=b.actual<=b.limit;double max=efficiencyWeight/budgets.size();assertions.add(assertion("EFFICIENCY",b.key,"预算 "+b.key,pass,false,pass?max:0,max,Map.of("limit",b.limit),Map.of("actual",b.actual),""));efficiencyScore+=pass?max:0;}
        double score=Math.min(100,contentScore+trajectoryScore+efficiencyScore);boolean hardPass=assertions.stream().filter(a->Boolean.TRUE.equals(a.get("hardGate"))).allMatch(a->Boolean.TRUE.equals(a.get("passed")));double passScore=dbl(expected,"passScore",75);boolean passed=hardPass&&score>=passScore;Map<String,Object>breakdown=Map.of("content",round(contentScore),"trajectory",round(trajectoryScore),"efficiency",round(efficiencyScore),"passScore",passScore,"toolPrecision",toolPrecision(requiredTools,forbiddenTools,actualTools));return new Grade(round(score),passed,assertions,breakdown);
    }

    Map<String,Object> gradeSnapshotForTest(Map<String,Object> definition,String output,Map<String,Object> trace){Grade grade=grade(definition,output,trace);return Map.of("score",grade.score,"passed",grade.passed,"assertions",grade.assertions,"breakdown",grade.breakdown);}
    private static double toolPrecision(List<String> required,List<String> forbidden,List<String> actual){if(actual.isEmpty())return required.isEmpty()?1:0;long good=actual.stream().filter(t->required.isEmpty()?!forbidden.contains(t):required.contains(t)).count();return round((double)good/actual.size());}
    private static void addBudget(List<Budget>b,String key,long actual,Map<String,Object>expected){if(expected.get(key) instanceof Number n)b.add(new Budget(key,actual,n.longValue()));}
    private static Map<String,Object> assertion(String grader,String key,String description,boolean passed,boolean hard,double score,double max,Object expected,Object actual,String details){Map<String,Object>a=new LinkedHashMap<>();a.put("graderType",grader);a.put("key",key);a.put("description",description);a.put("passed",passed);a.put("hardGate",hard);a.put("score",round(score));a.put("maxScore",round(max));a.put("expected",expected);a.put("actual",actual);a.put("details",details);return a;}
    private static String lastOutput(List<String> values){return values.stream().filter(v->v!=null&&!v.isBlank()).reduce((a,b)->b).orElse("");}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return v instanceof Map<?,?>m?(Map<String,Object>)m:Map.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Map<String,Object>m,String key){Object v=m.get(key);return v instanceof List<?>l?(List<Map<String,Object>>)l:List.of();}
    private static List<String> strings(Object v){return v instanceof Collection<?>c?c.stream().map(String::valueOf).toList():List.of();}
    private static List<Object> objects(Object v){return v instanceof Collection<?>c?new ArrayList<>(c):List.of();}
    private static boolean matchesCapability(Map<String,Object> execution,Object contract){if(!"SUCCESS".equals(execution.get("status")))return false;if(contract instanceof String text)return text.equalsIgnoreCase(Objects.toString(execution.get("name"),""))||text.equalsIgnoreCase(Objects.toString(execution.get("capabilityId"),""));if(!(contract instanceof Map<?,?> raw))return false;Map<String,Object> expected=new LinkedHashMap<>();raw.forEach((k,v)->expected.put(String.valueOf(k),v));return field(execution,"type",expected,"type")&&field(execution,"group",expected,"group")&&field(execution,"name",expected,"name")&&field(execution,"capabilityId",expected,"capabilityId")&&field(execution,"action",expected,"action")&&field(execution,"resourcePath",expected,"resourcePath");}
    private static boolean matchesResource(Map<String,Object> execution,Object contract){if(contract instanceof String path)return "SUCCESS".equals(execution.get("status"))&&path.equalsIgnoreCase(Objects.toString(execution.get("resourcePath"),""));return matchesCapability(execution,contract)&&execution.get("resourcePath")!=null&&!Objects.toString(execution.get("resourcePath"),"").isBlank();}
    private static boolean field(Map<String,Object> actual,String actualKey,Map<String,Object> expected,String expectedKey){Object wanted=expected.get(expectedKey);return wanted==null||Objects.toString(wanted,"").isBlank()||Objects.toString(wanted,"").equalsIgnoreCase(Objects.toString(actual.get(actualKey),""));}
    private static String capabilityLabel(Object contract){if(contract instanceof Map<?,?> map)return Objects.toString(map.get("name"),Objects.toString(map.get("capabilityId"),String.valueOf(contract)));return String.valueOf(contract);}
    private static String str(Map<String,Object>m,String k){return Objects.toString(m.get(k),"");}
    private static long number(Map<String,Object>m,String k,long d){return m.get(k)instanceof Number n?n.longValue():d;}
    private static double dbl(Map<String,Object>m,String k,double d){return m.get(k)instanceof Number n?n.doubleValue():d;}
    private static double round(double v){return Math.round(v*100.0)/100.0;}
    private static String message(Throwable e){Throwable c=e instanceof CompletionException&&e.getCause()!=null?e.getCause():e;String v=c.getMessage();return v==null?c.getClass().getSimpleName():v.substring(0,Math.min(v.length(),2000));}
    @PreDestroy public void close(){executor.shutdownNow();}
    private record Budget(String key,long actual,long limit){}
    private record Grade(double score,boolean passed,List<Map<String,Object>>assertions,Map<String,Object>breakdown){}
}
