package com.chinhbean.realtimechat.service;
import com.chinhbean.realtimechat.model.ChatMessage;
import org.springframework.messaging.simp.*;
import org.springframework.stereotype.Service;
@Service
public class ChatMessaging {
    private final SimpMessagingTemplate template;
    private final ChatRooms rooms;
    public ChatMessaging(SimpMessagingTemplate template, ChatRooms rooms) { this.template = template; this.rooms = rooms; }
    public void toSession(String sessionId, String queue, ChatMessage message) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId); headers.setLeaveMutable(true);
        template.convertAndSendToUser(sessionId, queue, message, headers.getMessageHeaders());
    }
    public void toUser(String username, ChatMessage message) {
        template.convertAndSendToUser(username, "/queue/private", message);
    }
    public void toPublic(ChatMessage message) { template.convertAndSend("/topic/publicChatRoom", message); }
    public void toRoom(ChatRooms.Change change, ChatMessage message) {
        message.setRoomId(change.roomId());
        synchronized (rooms) {
            for (String session : change.sessions()) {
                if (rooms.inRoom(change.roomId(), session)) toSession(session, "/queue/room", message);
            }
        }
    }
    public void roomLeft(ChatRooms.Change change) {
        if (change == null) return;
        ChatMessage message = event(ChatMessage.MessageType.LEAVE, change.username(), change.username() + " left the room.");
        message.setRoomUsers(change.users()); toRoom(change, message);
    }
    public ChatMessage event(ChatMessage.MessageType type, String username, String content) {
        var message = new ChatMessage();
        message.setType(type); message.setSender(username); message.setContent(content);
        message.setStatus(type == ChatMessage.MessageType.LEAVE ? "offline" : "online");
        message.setTimestamp(System.currentTimeMillis()); return message;
    }
}
