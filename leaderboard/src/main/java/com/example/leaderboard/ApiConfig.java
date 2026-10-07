package com.example.leaderboard;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class ApiConfig implements WebMvcConfigurer {
 private final AuthService auth;
 public ApiConfig(AuthService auth) { this.auth=auth; }
 @Override public void addInterceptors(InterceptorRegistry registry) {
  registry.addInterceptor(new HandlerInterceptor() {
   @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
    response.setHeader("Cache-Control","no-store");
    response.setHeader("X-Content-Type-Options","nosniff");
    if(request.getContentLengthLong()>16384) throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"Request body exceeds 16KB.");
    if(request.getDispatcherType()==DispatcherType.ASYNC) return true;
    String path=request.getRequestURI();
    if(path.equals("/api/auth/register") || path.equals("/api/auth/login") || path.equals("/api/health")) return true;
    request.setAttribute("identity",auth.authenticate(request.getHeader("Authorization")));
    return true;
   }
  }).addPathPatterns("/api/**");
 }
 static AuthService.Identity identity(HttpServletRequest request) { return (AuthService.Identity) request.getAttribute("identity"); }
}
