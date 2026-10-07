package com.chinhbean.realtimechat.service;
import org.springframework.stereotype.Service;
import java.util.*;
/** Membership transitions and snapshots share one lock; members are socket sessions. */
@Service
public class ChatRooms {
    public record Change(String roomId, String username, List<String> users, List<String> sessions) { }
    public record Transition(Change left, Change joined) { }
    private final Map<String, Map<String, String>> rooms = new HashMap<>();
    private final Map<String, String> sessionRooms = new HashMap<>();
    public synchronized String create() {
        String roomId = UUID.randomUUID().toString();
        rooms.put(roomId, new LinkedHashMap<>()); return roomId;
    }
    public synchronized void requireRoom(String roomId) {
        if (roomId == null || !rooms.containsKey(roomId)) throw new IllegalArgumentException("Room does not exist.");
    }
    public synchronized Change join(String roomId, String sessionId, String username) {
        requireRoom(roomId);
        if (sessionRooms.containsKey(sessionId) && !roomId.equals(sessionRooms.get(sessionId)))
            throw new IllegalStateException("Leave current room before joining another.");
        rooms.get(roomId).put(sessionId, username); sessionRooms.put(sessionId, roomId);
        return snapshot(roomId, username);
    }
    public synchronized Transition enter(String roomId, String sessionId, String username) {
        requireRoom(roomId);
        Change left = roomId.equals(sessionRooms.get(sessionId)) ? null : leave(sessionId);
        return new Transition(left, join(roomId, sessionId, username));
    }
    public synchronized Change leave(String sessionId) {
        String roomId = sessionRooms.remove(sessionId);
        if (roomId == null) return null;
        String username = rooms.get(roomId).remove(sessionId);
        Change change = snapshot(roomId, username);
        if (rooms.get(roomId).isEmpty()) rooms.remove(roomId);
        return change;
    }
    public synchronized Change members(String roomId, String sessionId) {
        if (roomId == null || !roomId.equals(sessionRooms.get(sessionId)))
            throw new IllegalArgumentException("Join this room before sending messages.");
        return snapshot(roomId, rooms.get(roomId).get(sessionId));
    }
    public synchronized boolean inRoom(String roomId, String sessionId) {
        return roomId != null && roomId.equals(sessionRooms.get(sessionId));
    }
    private Change snapshot(String roomId, String username) {
        var members = rooms.get(roomId);
        return new Change(roomId, username, members.values().stream().distinct().sorted().toList(), List.copyOf(members.keySet()));
    }
}
