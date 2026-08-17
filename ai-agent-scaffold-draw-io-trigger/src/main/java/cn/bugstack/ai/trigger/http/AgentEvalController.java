package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.eval.service.AgentEvalService;
import cn.bugstack.ai.trigger.http.auth.AuthenticatedUserContext;
import cn.bugstack.ai.types.enums.ResponseCode;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/eval")
public class AgentEvalController {
    private final AgentEvalService service;
    public AgentEvalController(AgentEvalService service){this.service=service;}

    @GetMapping("/datasets") public Response<List<Map<String,Object>>> datasets(){return ok(service.datasets(user()));}
    @PostMapping("/datasets") public Response<Map<String,Object>> createDataset(@RequestBody DatasetRequest r){return ok(Map.of("datasetId",service.createDataset(user(),required(r.key,"key"),required(r.name,"name"),r.description)));}
    @GetMapping("/datasets/{id}") public Response<Map<String,Object>> dataset(@PathVariable String id){return ok(service.dataset(user(),id));}
    @PostMapping("/datasets/{id}/cases") public Response<Map<String,Object>> createCase(@PathVariable String id,@RequestBody Map<String,Object> body){return ok(Map.of("caseId",service.createCase(user(),id,body)));}
    @PutMapping("/cases/{id}") public Response<Map<String,Object>> updateCase(@PathVariable String id,@RequestBody Map<String,Object> body){service.updateCase(user(),id,body);return ok(Map.of("updated",true));}
    @GetMapping("/runs") public Response<List<Map<String,Object>>> runs(@RequestParam(required=false)String datasetId){return ok(service.runs(user(),datasetId));}
    @PostMapping("/runs") public Response<Map<String,Object>> start(@RequestBody RunRequest r){String id=service.start(user(),required(r.datasetId,"datasetId"),required(r.candidateLabel,"candidateLabel"),r.repeats==null?1:r.repeats,r.baselineRunId);return ok(Map.of("runId",id,"status","QUEUED"));}
    @GetMapping("/runs/{id}") public Response<Map<String,Object>> run(@PathVariable String id){return ok(service.run(user(),id));}
    @PostMapping("/datasets/{datasetId}/baseline/{runId}") public Response<Map<String,Object>> baseline(@PathVariable String datasetId,@PathVariable String runId){service.baseline(user(),datasetId,runId);return ok(Map.of("updated",true));}
    @GetMapping("/invocations/{invocationId}") public Response<List<Map<String,Object>>> invocation(@PathVariable String invocationId){return ok(service.forInvocation(user(),invocationId));}

    private String user(){return AuthenticatedUserContext.current();}
    private static String required(String value,String name){if(value==null||value.isBlank())throw new IllegalArgumentException(name+" is required");return value.trim();}
    private <T>Response<T> ok(T data){return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();}
    public record DatasetRequest(String key,String name,String description){}
    public record RunRequest(String datasetId,String candidateLabel,Integer repeats,String baselineRunId){}
}
