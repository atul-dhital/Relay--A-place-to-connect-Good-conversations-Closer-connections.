package com.chinhbean.realtimechat.controller;
import com.chinhbean.realtimechat.model.ChatMessage;
import com.chinhbean.realtimechat.service.*;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import java.security.Principal;
@Controller
public class WebSocketController {
    private final ChatRooms rooms;
    private final ChatSessions sessions;
    private final ChatMessaging messages;
    public WebSocketController(ChatRooms rooms, ChatSessions sessions, ChatMessaging messages) {
        this.rooms = rooms; this.sessions = sessions; this.messages = messages;
    }
    private ChatMessage prepare(ChatMessage input, Principal user, ChatMessage.MessageType type) {
        if (user == null) throw new IllegalArgumentException("Login required.");
        String content = input.getContent() == null ? "" : input.getContent().trim();
        if (content.length() > 2000) throw new IllegalArgumentException("Messages may contain at most 2000 characters.");
        String image = input.getImageUrl();
        if (image != null && !image.matches("/images/[A-Za-z0-9_.-]+[.](png|jpg|jpeg|gif)"))
            throw new IllegalArgumentException("Invalid image URL.");
        if (content.isEmpty() && (image == null || image.isBlank())) throw new IllegalArgumentException("Enter a message or choose an image.");
        var result = messages.event(type, user.getName(), content);
        result.setImageUrl(image); return result;
    }
    @MessageMapping("/chat.sendMessage") public void sendMessage(@Payload ChatMessage input, Principal user) {
        messages.toPublic(prepare(input, user, ChatMessage.MessageType.CHAT));
    }
    @MessageMapping("/chat.addUser") public void addUser(Principal user) {
        messages.toPublic(messages.event(ChatMessage.MessageType.JOIN, user.getName(), user.getName() + " joined the chat."));
    }
    @MessageMapping("/chat.sendPrivateMessage") public void sendPrivateMessage(@Payload ChatMessage input, Principal user) {
        String recipient = input.getRecipient();
        if (recipient == null || !sessions.online(recipient)) throw new IllegalArgumentException("Recipient is not online.");
        var message = prepare(input, user, ChatMessage.MessageType.PRIVATE);
        message.setRecipient(recipient);
        messages.toUser(recipient, message);
        if (!recipient.equals(user.getName())) messages.toUser(user.getName(), message);
    }
    private void enterRoom(String roomId, Principal user, SimpMessageHeaderAccessor headers) {
        var transition = rooms.enter(roomId, headers.getSessionId(), user.getName());
        messages.roomLeft(transition.left());
        var change = transition.joined();
        var ack = messages.event(ChatMessage.MessageType.ROOM_JOINED, user.getName(), "Joined room " + roomId);
        ack.setRoomId(roomId); ack.setRoomUsers(change.users());
        messages.toSession(headers.getSessionId(), "/queue/private", ack);
        var joined = messages.event(ChatMessage.MessageType.JOIN, user.getName(), user.getName() + " joined the room.");
        joined.setRoomUsers(change.users()); messages.toRoom(change, joined);
    }
    @MessageMapping("/chat.createPrivateRoom") public void createPrivateRoom(Principal user, SimpMessageHeaderAccessor headers) {
        enterRoom(rooms.create(), user, headers);
    }
    @MessageMapping("/chat.joinRoom") public void joinRoom(@Payload ChatMessage input, Principal user, SimpMessageHeaderAccessor headers) {
        if (rooms.inRoom(input.getRoomId(), headers.getSessionId())) {
            var ack = messages.event(ChatMessage.MessageType.ROOM_JOINED, user.getName(), "Already in this room.");
            ack.setRoomId(input.getRoomId());
            ack.setRoomUsers(rooms.members(input.getRoomId(), headers.getSessionId()).users());
            messages.toSession(headers.getSessionId(), "/queue/private", ack);
            return;
        }
        enterRoom(input.getRoomId(), user, headers);
    }
    @MessageMapping("/chat.leaveRoom") public void leaveRoom(Principal user, SimpMessageHeaderAccessor headers) {
        messages.roomLeft(rooms.leave(headers.getSessionId()));
        messages.toSession(headers.getSessionId(), "/queue/private",
            messages.event(ChatMessage.MessageType.ROOM_LEFT, user.getName(), "Returned to public chat."));
    }
    @MessageMapping("/chat.sendRoomMessage") public void sendRoomMessage(@Payload ChatMessage input, Principal user, SimpMessageHeaderAccessor headers) {
        var change = rooms.members(input.getRoomId(), headers.getSessionId());
        messages.toRoom(change, prepare(input, user, ChatMessage.MessageType.CHAT));
    }
    @MessageExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public void handleError(Exception exception, Principal user, SimpMessageHeaderAccessor headers) {
        messages.toSession(headers.getSessionId(), "/queue/private",
            messages.event(ChatMessage.MessageType.ERROR, user == null ? "" : user.getName(), exception.getMessage()));
    }
}
