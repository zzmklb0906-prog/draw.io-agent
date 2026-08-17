package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.identity.adapter.IWorkspaceRepository;
import cn.bugstack.ai.trigger.http.auth.AuthenticatedUserContext;
import cn.bugstack.ai.types.enums.ResponseCode;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {
    private final IWorkspaceRepository repository;public WorkspaceController(IWorkspaceRepository repository){this.repository=repository;}
    public record WorkspaceRequest(String name,String description){}
    public record MemberRequest(String username,String role){}
    @GetMapping public Response<List<Map<String,Object>>> list(){return ok(repository.list(user()));}
    @PostMapping public Response<Map<String,Object>> create(@RequestBody WorkspaceRequest request){return ok(repository.create(user(),request.name(),request.description()));}
    @GetMapping("/{id}/members") public Response<List<Map<String,Object>>> members(@PathVariable UUID id){return ok(repository.members(user(),id));}
    @PutMapping("/{id}/members") public Response<Boolean> put(@PathVariable UUID id,@RequestBody MemberRequest request){repository.putMember(user(),id,request.username(),request.role());return ok(true);}
    @DeleteMapping("/{id}/members/{username}") public Response<Boolean> remove(@PathVariable UUID id,@PathVariable String username){repository.removeMember(user(),id,username);return ok(true);}
    private String user(){return AuthenticatedUserContext.current();}
    private <T> Response<T> ok(T data){return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();}
}

