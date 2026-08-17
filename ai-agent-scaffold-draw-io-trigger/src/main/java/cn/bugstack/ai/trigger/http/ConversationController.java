package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.conversation.adapter.IConversationRepository;
import cn.bugstack.ai.domain.conversation.model.ConversationView;
import cn.bugstack.ai.trigger.http.auth.AuthenticatedUserContext;
import cn.bugstack.ai.types.enums.ResponseCode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final IConversationRepository repository;
    public ConversationController(IConversationRepository repository){this.repository=repository;}
    public record CreateRequest(String agentId,String sessionId,String title){}
    public record AppendMessageRequest(String role,String type,String content,String contentJson,String invocationId){}
    public record StatusRequest(String status,String invocationId){}
    @PostMapping public Response<ConversationView> create(@RequestBody CreateRequest body){return ok(repository.create(user(),body.agentId(),body.sessionId(),body.title()));}
    @GetMapping public Response<List<ConversationView>> list(@RequestParam(defaultValue="50")int limit){return ok(repository.list(user(),limit));}
    @GetMapping("/{id}") public Response<ConversationView> get(@PathVariable UUID id){return ok(repository.get(user(),id).orElseThrow(()->new IllegalArgumentException("会话不存在")));}
    @PostMapping("/{id}/messages") public Response<ConversationView.MessageView> append(@PathVariable UUID id,@RequestBody AppendMessageRequest body){
        // Assistant/system messages are persisted only by the trusted runtime, never by a browser request.
        return ok(repository.append(user(),id,"user","TEXT",body.content(),null,null));
    }
    @PatchMapping("/{id}/status") public Response<Boolean> status(@PathVariable UUID id,@RequestBody StatusRequest body){repository.updateStatus(user(),id,body.status(),body.invocationId());return ok(true);}
    @DeleteMapping("/{id}") public Response<Boolean> delete(@PathVariable UUID id){repository.delete(user(),id);return ok(true);}
    private String user(){return AuthenticatedUserContext.current();}
    private <T> Response<T> ok(T data){return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();}
}
