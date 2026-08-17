package cn.bugstack.ai.trigger.http.auth;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import cn.bugstack.ai.domain.identity.adapter.ISecurityAuditRepository;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "agent_refresh";
    private final JwtAuthService auth;
    private final ISecurityAuditRepository audit;
    public AuthController(JwtAuthService auth,ISecurityAuditRepository audit){this.auth=auth;this.audit=audit;}
    public record LoginRequest(String username,String password){}

    @PostMapping("/login")
    public Response<Map<String,Object>> login(@RequestBody LoginRequest body,HttpServletRequest request,HttpServletResponse response){
        String ip=clientIp(request);JwtAuthService.Tokens tokens;
        try{tokens=auth.login(body.username(),body.password(),request.getHeader("User-Agent"),ip).orElse(null);}
        catch(AppException error){audit.record(body.username(),"AUTH_LOGIN","USER",body.username(),"RATE_LIMITED",ip,Map.of("code",error.getCode()));throw error;}
        if(tokens==null){audit.record(body.username(),"AUTH_LOGIN","USER",body.username(),"DENIED",ip,Map.of("reason","INVALID_CREDENTIALS"));throw new AppException("AUTH_INVALID","用户名或密码错误");}
        audit.record(tokens.username(),"AUTH_LOGIN","USER",tokens.username(),"SUCCESS",ip,Map.of());
        refreshCookie(response,tokens.refreshToken(),tokens.refreshExpiresAt());
        return ok(Map.of("username",tokens.username(),"displayName",tokens.displayName(),"token",tokens.accessToken(),"expiresAt",tokens.accessExpiresAt()));
    }

    @PostMapping("/refresh")
    public Response<Map<String,Object>> refresh(HttpServletRequest request,HttpServletResponse response){
        String ip=clientIp(request);var tokens=auth.refresh(cookie(request,REFRESH_COOKIE),request.getHeader("User-Agent"),ip).orElse(null);
        if(tokens==null){audit.record(null,"AUTH_REFRESH","TOKEN","refresh","DENIED",ip,Map.of());throw new AppException("AUTH_REFRESH_INVALID","刷新凭证无效或已过期");}
        audit.record(tokens.username(),"AUTH_REFRESH","TOKEN","refresh","SUCCESS",ip,Map.of());
        refreshCookie(response,tokens.refreshToken(),tokens.refreshExpiresAt());
        return ok(Map.of("username",tokens.username(),"displayName",tokens.displayName(),"token",tokens.accessToken(),"expiresAt",tokens.accessExpiresAt()));
    }

    @PostMapping("/logout")
    public Response<Map<String,Object>> logout(@RequestHeader(value="Authorization",required=false)String authorization,HttpServletRequest request,HttpServletResponse response){
        var principal=auth.authenticate(bearer(authorization));auth.logout(bearer(authorization),cookie(request,REFRESH_COOKIE));
        principal.ifPresent(value->audit.record(value.username(),"AUTH_LOGOUT","USER",value.id().toString(),"SUCCESS",clientIp(request),Map.of()));
        Cookie expired=new Cookie(REFRESH_COOKIE,"");expired.setHttpOnly(true);expired.setPath("/api/v1/auth");expired.setMaxAge(0);response.addCookie(expired);
        return ok(Map.of("loggedOut",true));
    }

    private void refreshCookie(HttpServletResponse response,String token,long expiresAt){Cookie cookie=new Cookie(REFRESH_COOKIE,token);cookie.setHttpOnly(true);cookie.setSecure(false);cookie.setPath("/api/v1/auth");cookie.setMaxAge((int)Math.max(1,(expiresAt-System.currentTimeMillis())/1000));cookie.setAttribute("SameSite","Lax");response.addCookie(cookie);}
    private String cookie(HttpServletRequest request,String name){return request.getCookies()==null?null:Arrays.stream(request.getCookies()).filter(c->name.equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);}
    private String bearer(String value){return value!=null&&value.startsWith("Bearer ")?value.substring(7):null;}
    private String clientIp(HttpServletRequest request){String value=request.getHeader("X-Forwarded-For");return value==null||value.isBlank()?request.getRemoteAddr():value.split(",")[0].trim();}
    private <T> Response<T> ok(T data){return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();}
}
