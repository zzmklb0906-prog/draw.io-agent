package cn.bugstack.ai.trigger.http.auth;

import com.alibaba.fastjson.JSON;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class ApiAuthenticationInterceptor implements HandlerInterceptor {
    private final JwtAuthService auth;public ApiAuthenticationInterceptor(JwtAuthService auth){this.auth=auth;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{if("OPTIONS".equalsIgnoreCase(request.getMethod()))return true;String header=request.getHeader("Authorization");String token=header!=null&&header.startsWith("Bearer ")?header.substring(7):null;var principal=auth.authenticate(token);if(principal.isEmpty()){reject(response,401,"AUTH_REQUIRED","请先登录或重新登录");return false;}String claimed=request.getHeader("X-User-Id");if(claimed!=null&&!claimed.isBlank()&&!claimed.equals(principal.get().username())){reject(response,403,"AUTH_USER_MISMATCH","X-User-Id 必须与访问令牌身份一致");return false;}request.setAttribute("authenticatedUserId",principal.get().username());request.setAttribute("authenticatedUserUuid",principal.get().id());request.setAttribute("authenticatedRoles",principal.get().roles());return true;}
    private void reject(HttpServletResponse response,int status,String code,String info)throws Exception{response.setStatus(status);response.setCharacterEncoding(StandardCharsets.UTF_8.name());response.setContentType("application/json");response.getWriter().write(JSON.toJSONString(Map.of("code",code,"info",info)));}
}
