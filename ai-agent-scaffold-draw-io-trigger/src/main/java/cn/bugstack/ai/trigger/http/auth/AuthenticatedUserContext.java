package cn.bugstack.ai.trigger.http.auth;

import cn.bugstack.ai.types.exception.AppException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class AuthenticatedUserContext {
    private AuthenticatedUserContext(){}
    public static String current(){var attributes=(ServletRequestAttributes)RequestContextHolder.currentRequestAttributes();Object user=attributes.getRequest().getAttribute("authenticatedUserId");if(user==null)throw new AppException("AUTH_REQUIRED","缺少认证用户");return String.valueOf(user);}
    public static String require(String claimed){String current=current();if(claimed==null||!current.equals(claimed))throw new AppException("AUTH_USER_MISMATCH","请求 userId 与登录用户不一致");return current;}
    @SuppressWarnings("unchecked") public static java.util.List<String> roles(){var attributes=(ServletRequestAttributes)RequestContextHolder.currentRequestAttributes();Object value=attributes.getRequest().getAttribute("authenticatedRoles");return value instanceof java.util.List<?> list?list.stream().map(String::valueOf).toList():java.util.List.of();}
    public static void requireRole(String role){if(!roles().contains(role))throw new AppException("AUTH_FORBIDDEN","当前用户缺少权限: "+role);}
}
