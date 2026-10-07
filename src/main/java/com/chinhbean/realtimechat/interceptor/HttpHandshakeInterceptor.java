package com.chinhbean.realtimechat.interceptor;
import com.chinhbean.realtimechat.service.ChatSessions;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;
@Component
public class HttpHandshakeInterceptor implements HandshakeInterceptor {
    private final ChatSessions sessions;
    public HttpHandshakeInterceptor(ChatSessions sessions) { this.sessions = sessions; }
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servlet) {
            var session = servlet.getServletRequest().getSession(false);
            String username = session == null ? null : (String) session.getAttribute("username");
            if (session != null && sessions.active(username, session.getId())) {
                attributes.put("username", username); attributes.put("httpSessionId", session.getId()); return true;
            }
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED); return false;
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) { }
}
