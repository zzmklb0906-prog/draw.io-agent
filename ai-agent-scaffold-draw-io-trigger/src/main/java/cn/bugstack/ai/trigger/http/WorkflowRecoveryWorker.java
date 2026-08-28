package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkflowRecoveryQueueRepository;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowCheckpointEntity;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowRecoveryJob;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.workflow.WorkflowCheckpointService;
import cn.bugstack.ai.domain.artifact.adapter.IArtifactRepository;
import cn.bugstack.ai.domain.conversation.adapter.IConversationRepository;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.adk.events.Event;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class WorkflowRecoveryWorker {
    private final IWorkflowRecoveryQueueRepository queue;private final IChatService chat;private final WorkflowCheckpointService checkpoints;private final IConversationRepository conversations;private final IArtifactRepository artifacts;
    private final ExecutorService workers;private final AtomicInteger active=new AtomicInteger();private final String instanceId="recovery:"+ManagementFactory.getRuntimeMXBean().getName()+":"+UUID.randomUUID().toString().substring(0,8);
    @Value("${ai.agent.recovery.worker-concurrency:2}") private int concurrency;
    @Value("${ai.agent.recovery.max-attempts:3}") private int maxAttempts;
    public WorkflowRecoveryWorker(IWorkflowRecoveryQueueRepository queue,IChatService chat,WorkflowCheckpointService checkpoints,IConversationRepository conversations,IArtifactRepository artifacts){this.queue=queue;this.chat=chat;this.checkpoints=checkpoints;this.conversations=conversations;this.artifacts=artifacts;this.workers=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"workflow-recovery");t.setDaemon(true);return t;});}

    @Scheduled(fixedDelayString="${ai.agent.recovery.dispatch-ms:3000}",initialDelay=8000)
    public void dispatch(){while(active.get()<Math.max(1,Math.min(concurrency,2))){var job=queue.claim(instanceId);if(job.isEmpty())return;active.incrementAndGet();workers.submit(()->{try{execute(job.get());}finally{active.decrementAndGet();}});}}

    private void execute(WorkflowRecoveryJob job){AtomicReference<String> invocation=new AtomicReference<>();try{
        WorkflowCheckpointEntity cp=checkpoints.resumeRecovery(job.checkpointId(),job.checkpointRevision());
        String entry="ANALYSIS".equalsIgnoreCase(cp.getStage())?"agent_analyst":"DRAWING".equalsIgnoreCase(cp.getStage())?"agent_drawer":null;
        String prompt="DRAWING".equalsIgnoreCase(cp.getStage())?"[APPROVED_DRAWING_BRIEF]\n"+approvedPrompt(job.approvalJson()):job.originalPrompt();
        StringBuilder output=new StringBuilder();
        chat.handleMessageStream(job.agentId(),job.username(),job.sessionId(),prompt,entry,"recovery:"+job.jobId()).blockingForEach(event->{invocation.compareAndSet(null,event.invocationId());String text=event.stringifyContent();if(text!=null)output.append(text);});
        String raw=output.toString(),inv=invocation.get();
        if("ANALYSIS".equalsIgnoreCase(cp.getStage())){
            String approval=structured(raw,"approval");if(StringUtils.isBlank(approval))throw new IllegalStateException("Recovered analyst did not return approval payload");
            checkpoints.approval(job.checkpointId(),inv,approval);JSONObject parsed=JSON.parseObject(approval);String text=parsed==null?"等待审核":StringUtils.defaultIfBlank(parsed.getString("rewrittenPrompt"),"等待审核");
            conversations.append(job.username(),job.conversationId(),"assistant","APPROVAL",text,approval,inv,"recovery:"+job.jobId()+":approval");conversations.updateStatus(job.username(),job.conversationId(),"WAITING_APPROVAL",inv);
        }else{
            String xml=structuredField(raw,"drawio_done","content");if(StringUtils.isBlank(xml))xml=structuredField(raw,"drawio","content");
            if(StringUtils.isBlank(xml))throw new IllegalStateException("Recovered drawer did not return Draw.io XML");
            JSONObject payload=new JSONObject();payload.put("xml",xml);payload.put("checkpointId",job.checkpointId());String reply="任务在实例中断后已从 Checkpoint 恢复并完成绘图。";
            conversations.append(job.username(),job.conversationId(),"assistant","DRAWIO",reply,payload.toJSONString(),inv,"recovery:"+job.jobId()+":drawio-message");
            artifacts.save(job.conversationId(),inv,"DRAWIO","Draw.io 图表（恢复）","application/vnd.jgraph.mxfile",xml,"{}","recovery:"+job.jobId()+":drawio-artifact");checkpoints.finish(job.checkpointId(),true,"");conversations.updateStatus(job.username(),job.conversationId(),"COMPLETED",inv);
        }
        queue.complete(job.jobId(),inv);
    }catch(Exception e){log.error("Workflow recovery failed jobId={} checkpointId={}",job.jobId(),job.checkpointId(),e);queue.retryOrFail(job.jobId(),String.valueOf(e.getMessage()),maxAttempts,System.currentTimeMillis()+5000L);}}

    private String approvedPrompt(String json){try{JSONObject o=JSON.parseObject(json);return o==null?"":StringUtils.defaultString(o.getString("rewrittenPrompt"));}catch(Exception e){return "";}}
    private String structured(String raw,String type){for(String line:raw.split("\\R")){try{JSONObject o=JSON.parseObject(line.trim());if(type.equals(o.getString("type")))return o.toJSONString();}catch(Exception ignored){}}return "";}
    private String structuredField(String raw,String type,String field){String json=structured(raw,type);if(json.isBlank())return "";JSONObject o=JSON.parseObject(json);return StringUtils.defaultString(o.getString(field));}
}
