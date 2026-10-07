package com.chinhbean.realtimechat;
import com.chinhbean.realtimechat.service.ChatRooms;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChatRoomsTests {
    @Test void membershipTracksTabsAndRemovesEmptyRooms() {
        var rooms = new ChatRooms();
        var room = rooms.create();
        rooms.join(room, "tab-1", "alice"); rooms.join(room, "tab-2", "alice");
        var remaining = rooms.leave("tab-1");
        assertEquals(java.util.List.of("alice"), remaining.users());
        assertTrue(rooms.inRoom(room, "tab-2"));
        assertThrows(IllegalArgumentException.class, () -> rooms.members(room, "outsider"));
        rooms.leave("tab-2");
        assertThrows(IllegalArgumentException.class, () -> rooms.requireRoom(room));
        assertNull(rooms.leave("tab-2"));
    }
    @Test void unknownRoomsCannotBeCreatedByJoining() {
        var rooms = new ChatRooms();
        assertThrows(IllegalArgumentException.class, () -> rooms.join("guessed-id", "socket", "alice"));
    }
    @Test void failedRoomSwitchKeepsCurrentMembership() {
        var rooms = new ChatRooms();
        var original = rooms.create();
        rooms.join(original, "tab", "alice");
        assertThrows(IllegalArgumentException.class, () -> rooms.enter("missing", "tab", "alice"));
        assertTrue(rooms.inRoom(original, "tab"));
        var next = rooms.create();
        var change = rooms.enter(next, "tab", "alice");
        assertEquals(original, change.left().roomId());
        assertTrue(rooms.inRoom(next, "tab"));
        assertThrows(IllegalArgumentException.class, () -> rooms.requireRoom(original));
    }
}
