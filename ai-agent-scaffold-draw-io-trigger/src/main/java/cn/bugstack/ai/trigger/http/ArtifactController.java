package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.artifact.adapter.IArtifactRepository;
import cn.bugstack.ai.domain.artifact.model.ArtifactView;
import cn.bugstack.ai.trigger.http.auth.AuthenticatedUserContext;
import cn.bugstack.ai.types.enums.ResponseCode;
import org.springframework.web.bind.annotation.*;
import cn.bugstack.ai.domain.idempotency.service.IdempotencyService;
import com.alibaba.fastjson.JSON;
import java.util.*;

@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {
    private final IArtifactRepository repository;private final IdempotencyService idempotency;public ArtifactController(IArtifactRepository repository,IdempotencyService idempotency){this.repository=repository;this.idempotency=idempotency;}
    public record RevisionRequest(String invocationId,String idempotencyKey,String branchName){}
    @GetMapping public Response<List<ArtifactView>> list(@RequestParam UUID conversationId){return ok(repository.list(user(),conversationId));}
    @GetMapping("/{id}") public Response<ArtifactView> get(@PathVariable UUID id){return ok(repository.get(user(),id));}
    @GetMapping("/{target}/diff/{base}") public Response<String> diff(@PathVariable UUID target,@PathVariable UUID base){return ok(repository.diff(user(),base,target));}
    @PostMapping("/{id}/rollback") public Response<ArtifactView> rollback(@PathVariable UUID id,@RequestBody RevisionRequest body){return revision("ARTIFACT_ROLLBACK",id,body,()->repository.rollback(user(),id,body.invocationId(),body.idempotencyKey()));}
    @PostMapping("/{id}/branches") public Response<ArtifactView> branch(@PathVariable UUID id,@RequestBody RevisionRequest body){return revision("ARTIFACT_BRANCH",id,body,()->repository.branch(user(),id,body.branchName(),body.invocationId(),body.idempotencyKey()));}
    private Response<ArtifactView> revision(String scope,UUID source,RevisionRequest request,java.util.function.Supplier<ArtifactView> operation){String owner=user();var claim=idempotency.begin(owner,scope,request.idempotencyKey(),JSON.toJSONString(Map.of("source",source,"invocationId",Objects.toString(request.invocationId(),""),"branchName",Objects.toString(request.branchName(),""))));if(claim.replay())return ok(repository.get(owner,UUID.fromString(claim.record().resourceId())));try{ArtifactView result=operation.get();idempotency.complete(owner,scope,request.idempotencyKey(),result.id().toString(),JSON.toJSONString(result));return ok(result);}catch(RuntimeException error){idempotency.fail(owner,scope,request.idempotencyKey(),error);throw error;}}
    private String user(){return AuthenticatedUserContext.current();}private <T> Response<T> ok(T data){return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();}
}
