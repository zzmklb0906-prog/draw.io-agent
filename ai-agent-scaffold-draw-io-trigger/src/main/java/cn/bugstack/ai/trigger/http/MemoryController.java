package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.memory.model.entity.AgentMemoryEntity;
import cn.bugstack.ai.domain.agent.memory.service.MemoryService;
import cn.bugstack.ai.domain.agent.memory.service.MemoryConsolidationService;
import cn.bugstack.ai.types.enums.ResponseCode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {
    private final MemoryService service;private final MemoryConsolidationService consolidation;public MemoryController(MemoryService service,MemoryConsolidationService consolidation){this.service=service;this.consolidation=consolidation;}
    public record MemoryRequest(String userId,String projectId,String memoryType,String content,String structuredData,Double importance,Double confidence,Boolean confirmed,String sourceSessionId,String sourceEventId,Long expiresAt){}
    public record UpdateMemoryRequest(String content,String structuredData,double importance,boolean confirmed){}
    @PostMapping public Response<AgentMemoryEntity> create(@RequestHeader(value="X-User-Id",defaultValue="admin")String userId,@RequestBody MemoryRequest r){if(r.userId()!=null&&!userId.equals(r.userId()))throw new cn.bugstack.ai.types.exception.AppException("MEMORY_ACCESS_DENIED","不能为其他用户创建 Memory");AgentMemoryEntity m=AgentMemoryEntity.builder().userId(userId).projectId(r.projectId()).memoryType(r.memoryType()).content(r.content()).structuredData(r.structuredData()).importance(r.importance()==null?.5:r.importance()).confidence(r.confidence()==null?.5:r.confidence()).confirmed(Boolean.TRUE.equals(r.confirmed())).sourceSessionId(r.sourceSessionId()).sourceEventId(r.sourceEventId()).expiresAt(r.expiresAt()).build();return ok(service.create(m));}
    @GetMapping public Response<List<AgentMemoryEntity>> list(@RequestHeader(value="X-User-Id",defaultValue="admin")String authenticatedUser,@RequestParam String userId,@RequestParam(required=false)String projectId,@RequestParam(required=false)String type,@RequestParam(defaultValue="100")int limit){if(!authenticatedUser.equals(userId))throw new cn.bugstack.ai.types.exception.AppException("MEMORY_ACCESS_DENIED","无权读取其他用户 Memory");return ok(service.list(userId,projectId,type,limit));}
    @GetMapping("/retrieve") public Response<List<AgentMemoryEntity>> retrieve(@RequestHeader(value="X-User-Id",defaultValue="admin")String authenticatedUser,@RequestParam String userId,@RequestParam(required=false)String projectId,@RequestParam String query,@RequestParam(defaultValue="8")int limit){if(!authenticatedUser.equals(userId))throw new cn.bugstack.ai.types.exception.AppException("MEMORY_ACCESS_DENIED","无权检索其他用户 Memory");return ok(service.retrieve(userId,projectId,query,limit));}
    @PostMapping("/{id}/confirm") public Response<AgentMemoryEntity> confirm(@RequestHeader(value="X-User-Id",defaultValue="admin")String userId,@PathVariable String id){return ok(service.confirmOwned(id,userId));}
    @GetMapping("/{id}/evidence") public Response<List<cn.bugstack.ai.domain.agent.memory.model.entity.MemoryEvidenceEntity>> evidence(@RequestHeader(value="X-User-Id",defaultValue="admin")String userId,@PathVariable String id){return ok(service.evidenceOwned(id,userId));}
    @PostMapping("/consolidate") public Response<Map<String,Object>> consolidate(@RequestHeader(value="X-User-Id",defaultValue="admin")String userId,@RequestParam(required=false)String projectId){return ok(consolidation.consolidate(userId,projectId));}
    @PutMapping("/{id}") public Response<AgentMemoryEntity> update(@RequestHeader(value="X-User-Id",defaultValue="admin")String userId,@PathVariable String id,@RequestBody UpdateMemoryRequest r){return ok(service.updateOwned(id,userId,r.content(),r.structuredData(),r.importance(),r.confirmed()));}
    @DeleteMapping("/{id}") public Response<Map<String,Object>> delete(@RequestHeader(value="X-User-Id",defaultValue="admin")String userId,@PathVariable String id){service.deleteOwned(id,userId);return ok(Map.of("deleted",true));}
    private <T> Response<T> ok(T d){return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(d).build();}
}
