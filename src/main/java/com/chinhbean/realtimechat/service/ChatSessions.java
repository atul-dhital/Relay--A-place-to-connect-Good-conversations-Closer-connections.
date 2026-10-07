package com.chinhbean.realtimechat.service;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.*;
@Service
public class ChatSessions {
    private final Map<String, String> users = new HashMap<>();
    private final Map<String, WebSocketSession> sockets = new HashMap<>();
    public synchronized boolean reserve(String username, String httpSessionId) {
        if (users.containsKey(username)) return false;
        users.put(username, httpSessionId); return true;
    }
    public synchronized boolean active(String username, String httpSessionId) {
        return username != null && httpSessionId != null && httpSessionId.equals(users.get(username));
    }
    public synchronized boolean online(String username) {
        return users.containsKey(username) && sockets.values().stream()
            .anyMatch(s -> s.isOpen() && username.equals(s.getAttributes().get("username")));
    }
    public synchronized void register(WebSocketSession socket) throws IOException {
        var attrs = socket.getAttributes();
        if (!active((String) attrs.get("username"), (String) attrs.get("httpSessionId"))) {
            socket.close(CloseStatus.POLICY_VIOLATION); return;
        }
        sockets.put(socket.getId(), socket);
    }
    public synchronized void unregister(String socketId) { sockets.remove(socketId); }
    public void reject(String socketId) {
        WebSocketSession socket;
        synchronized (this) { socket = sockets.get(socketId); }
        if (socket != null) {
            try { socket.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) { }
        }
    }
    public void revoke(String httpSessionId) {
        List<WebSocketSession> closing;
        synchronized (this) {
            users.values().removeIf(httpSessionId::equals);
            closing = sockets.values().stream()
                .filter(s -> httpSessionId.equals(s.getAttributes().get("httpSessionId"))).toList();
        }
        for (var socket : closing) {
            try { socket.close(CloseStatus.NORMAL); } catch (IOException ignored) { }
        }
    }
}
