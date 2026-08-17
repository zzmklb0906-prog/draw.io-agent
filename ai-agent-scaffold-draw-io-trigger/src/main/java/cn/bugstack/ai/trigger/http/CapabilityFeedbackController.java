package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.adapter.repository.IRuntimeObservationRepository;
import cn.bugstack.ai.trigger.http.auth.AuthenticatedUserContext;
import cn.bugstack.ai.types.enums.ResponseCode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/monitor/invocations")
public class CapabilityFeedbackController {
    private final IRuntimeObservationRepository repository;public CapabilityFeedbackController(IRuntimeObservationRepository repository){this.repository=repository;}
    public record FeedbackRequest(String searchId,String capabilityId,String judgment,String note){}
    @PostMapping("/{invocationId}/capability-feedback") public Response<Boolean> feedback(@PathVariable String invocationId,@RequestBody FeedbackRequest request){repository.capabilityFeedback(AuthenticatedUserContext.current(),invocationId,request.searchId(),request.capabilityId(),request.judgment(),request.note());return Response.<Boolean>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(true).build();}
}

