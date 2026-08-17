package cn.bugstack.ai.trigger.http.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebAuthConfiguration implements WebMvcConfigurer {
    private final ApiAuthenticationInterceptor interceptor;public WebAuthConfiguration(ApiAuthenticationInterceptor interceptor){this.interceptor=interceptor;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**").excludePathPatterns("/api/v1/auth/login","/api/v1/auth/refresh");}
    @Override public void addCorsMappings(CorsRegistry registry){registry.addMapping("/api/**").allowedOriginPatterns("http://localhost:*","http://127.0.0.1:*").allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS").allowedHeaders("*").allowCredentials(true).maxAge(3600);}
}
