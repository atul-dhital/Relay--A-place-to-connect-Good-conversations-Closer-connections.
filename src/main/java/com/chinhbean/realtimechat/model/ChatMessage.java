package com.chinhbean.realtimechat.model;
import java.util.List;
public class ChatMessage {
    public enum MessageType { CHAT, JOIN, LEAVE, PRIVATE, ROOM_JOINED, ROOM_LEFT, ERROR }
    private MessageType type;
    private String content;
    private String sender;
    private String recipient;
    private String status;
    private String roomId;
    private String imageUrl;
    private List<String> roomUsers;
    private long timestamp;
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public List<String> getRoomUsers() { return roomUsers; }
    public void setRoomUsers(List<String> roomUsers) { this.roomUsers = roomUsers; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
