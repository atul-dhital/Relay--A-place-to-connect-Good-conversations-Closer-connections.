package com.chinhbean.realtimechat.config;
import com.chinhbean.realtimechat.service.ChatSessions;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;
@Configuration
public class HttpSessionConfig {
    @Bean public ServletListenerRegistrationBean<HttpSessionListener> chatSessionListener(ChatSessions sessions) {
        return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent event) { sessions.revoke(event.getSession().getId()); }
        });
    }
}
