package com.chinhbean.realtimechat.config;
import com.chinhbean.realtimechat.service.ChatSessions;
import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.file.Path;
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ChatSessions sessions;
    private final Path uploadDir;
    public WebConfig(ChatSessions sessions, Environment environment) {
        this.sessions = sessions;
        this.uploadDir = Path.of(environment.getProperty("file.upload-dir", "uploads/images")).toAbsolutePath().normalize();
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**").addResourceLocations(uploadDir.toUri().toString() + "/", "classpath:/static/images/");
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                var session = request.getSession(false);
                if (!request.getRequestURI().equals("/login") && (session == null ||
                    !sessions.active((String) session.getAttribute("username"), session.getId()))) {
                    response.sendError(401, "Login required."); return false;
                }
                if (request.getMethod().equals("POST")) {
                    String expected = session == null ? null : (String) session.getAttribute("csrfToken");
                    String supplied = request.getHeader("X-CSRF-Token");
                    if (supplied == null) supplied = request.getParameter("_csrf");
                    if (expected == null || !expected.equals(supplied)) {
                        response.sendError(403, "Invalid request token. Reload the page."); return false;
                    }
                }
                return true;
            }
        }).addPathPatterns("/login", "/logout", "/upload");
    }
}
