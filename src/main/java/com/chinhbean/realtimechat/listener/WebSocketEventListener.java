package com.chinhbean.realtimechat.listener;
import com.chinhbean.realtimechat.model.ChatMessage;
import com.chinhbean.realtimechat.service.*;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import java.util.HashMap;
import java.util.Map;
@Component
public class WebSocketEventListener {
    private final ChatRooms rooms;
    private final ChatMessaging messages;
    private final Map<String, String> connected = new HashMap<>();
    public WebSocketEventListener(ChatRooms rooms, ChatMessaging messages) { this.rooms = rooms; this.messages = messages; }
    @EventListener public synchronized void connected(SessionConnectedEvent event) {
        if (event.getUser() != null)
            connected.put(StompHeaderAccessor.wrap(event.getMessage()).getSessionId(), event.getUser().getName());
    }
    @EventListener public synchronized void disconnected(SessionDisconnectEvent event) {
        messages.roomLeft(rooms.leave(event.getSessionId()));
        String username = connected.remove(event.getSessionId());
        if (username != null && !connected.containsValue(username))
            messages.toPublic(messages.event(ChatMessage.MessageType.LEAVE, username, username + " disconnected."));
    }
}
