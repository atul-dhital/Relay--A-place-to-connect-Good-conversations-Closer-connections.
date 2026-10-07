package com.chinhbean.realtimechat;

import com.chinhbean.realtimechat.model.ChatMessage;
import com.chinhbean.realtimechat.service.ChatRooms;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatIntegrationTests {
    @LocalServerPort int port;
    @Autowired ChatRooms rooms;
    static Path uploads;
    static {
        try { uploads = Files.createTempDirectory("chat-test-uploads-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) {
        properties.add("file.upload-dir", uploads::toString);
    }
    final HttpClient http = HttpClient.newHttpClient();
    final List<Connection> sockets = new ArrayList<>();
    final List<Identity> identities = new ArrayList<>();
    record Identity(String cookie, String token) { }
    record Connection(WebSocketStompClient client, StompSession session, BlockingQueue<ChatMessage> privateQueue,
                      BlockingQueue<ChatMessage> roomQueue, BlockingQueue<ChatMessage> publicQueue,
                      org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler) { }

    HttpResponse<String> get(String path, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) request.header("Cookie", cookie);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    String cookie(HttpResponse<?> response) {
        return response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }
    String token(String html) {
        var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), "Rendered CSRF token missing"); return matcher.group(1);
    }
    Identity login(String username) throws Exception {
        var form = get("/login", null);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
            .header("Cookie", cookie(form)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&_csrf=" + token(form.body()))).build();
        var logged = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(302, logged.statusCode(), logged.body());
        String sessionCookie = cookie(logged);
        var page = get("/", sessionCookie);
        var identity = new Identity(sessionCookie, token(page.body()));
        identities.add(identity); return identity;
    }
    StompFrameHandler queue(BlockingQueue<ChatMessage> queue) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return ChatMessage.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { queue.add((ChatMessage) payload); }
        };
    }
    Connection connect(Identity identity) throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.initialize(); client.setTaskScheduler(scheduler);
        var headers = new WebSocketHttpHeaders(); headers.add("Cookie", identity.cookie());
        var session = client.connectAsync("ws://localhost:" + port + "/ws/websocket", headers,
            new StompHeaders(), new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
        var privateQueue = new LinkedBlockingQueue<ChatMessage>();
        var roomQueue = new LinkedBlockingQueue<ChatMessage>();
        var publicQueue = new LinkedBlockingQueue<ChatMessage>();
        session.subscribe("/user/queue/private", queue(privateQueue));
        session.subscribe("/user/queue/room", queue(roomQueue));
        session.subscribe("/topic/publicChatRoom", queue(publicQueue));
        session.send("/app/chat.addUser", Map.of());
        await(publicQueue, m -> m.getType() == ChatMessage.MessageType.JOIN);
        var connection = new Connection(client, session, privateQueue, roomQueue, publicQueue, scheduler);
        sockets.add(connection); return connection;
    }
    ChatMessage await(BlockingQueue<ChatMessage> queue, Predicate<ChatMessage> wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var message = queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (message != null && wanted.test(message)) return message;
        }
        fail("Expected message not received"); return null;
    }
    void logout(Identity identity) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/logout"))
            .header("Cookie", identity.cookie()).header("X-CSRF-Token", identity.token())
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        http.send(request, HttpResponse.BodyHandlers.discarding());
    }
    @AfterEach void cleanup() throws Exception {
        for (var socket : sockets) { if (socket.session().isConnected()) socket.session().disconnect(); socket.client().stop(); socket.scheduler().shutdown(); }
        for (var identity : identities) logout(identity);
    }
    @AfterAll static void cleanupUploads() throws IOException {
        try (var stored = Files.list(uploads)) { for (var file : stored.toList()) Files.delete(file); }
        Files.delete(uploads);
    }

    @Test void publicAndPrivateMessagesUseSessionIdentityIncludingImagesWithoutText() throws Exception {
        var alice = connect(login("privateAlice"));
        var bob = connect(login("privateBob"));
        alice.session().send("/app/chat.sendMessage", Map.of("sender", "forgedBob", "content", "hello public"));
        var publicMessage = await(bob.publicQueue(), m -> "hello public".equals(m.getContent()));
        assertEquals("privateAlice", publicMessage.getSender()); assertTrue(publicMessage.getTimestamp() > 0);
        alice.session().send("/app/chat.sendPrivateMessage", Map.of("sender", "forgedBob", "recipient", "privateBob", "imageUrl", "/images/test.png"));
        var privateMessage = await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.PRIVATE);
        assertEquals("privateAlice", privateMessage.getSender()); assertEquals("", privateMessage.getContent());
        assertEquals("/images/test.png", privateMessage.getImageUrl());
        await(alice.privateQueue(), m -> m.getType() == ChatMessage.MessageType.PRIVATE);
    }
    @Test void roomsRejectOutsidersTrackMembersAndCleanUpOnLogout() throws Exception {
        var aliceIdentity = login("roomAlice"); var alice = connect(aliceIdentity);
        var bobIdentity = login("roomBob"); var bob = connect(bobIdentity);
        alice.session().send("/app/chat.createPrivateRoom", Map.of());
        var created = await(alice.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ROOM_JOINED);
        String roomId = created.getRoomId(); assertNotNull(roomId);
        bob.session().send("/app/chat.sendRoomMessage", Map.of("roomId", roomId, "content", "unauthorized"));
        await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ERROR);
        assertNull(alice.roomQueue().stream().filter(m -> "unauthorized".equals(m.getContent())).findFirst().orElse(null));
        bob.session().send("/app/chat.joinRoom", Map.of("roomId", "unknown-room"));
        await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ERROR);
        bob.session().send("/app/chat.joinRoom", Map.of("roomId", roomId));
        var joined = await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ROOM_JOINED);
        assertEquals(List.of("roomAlice", "roomBob"), joined.getRoomUsers());
        bob.session().send("/app/chat.joinRoom", Map.of("roomId", roomId));
        var repeated = await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ROOM_JOINED);
        assertEquals(roomId, repeated.getRoomId());
        bob.session().send("/app/chat.joinRoom", Map.of("roomId", "missing-room"));
        await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ERROR);
        bob.session().send("/app/chat.sendRoomMessage", Map.of("roomId", roomId, "content", "room hello", "sender", "forged"));
        assertEquals("roomBob", await(alice.roomQueue(), m -> "room hello".equals(m.getContent())).getSender());
        bob.session().send("/app/chat.leaveRoom", Map.of());
        await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ROOM_LEFT);
        assertEquals(List.of("roomAlice"), await(alice.roomQueue(), m -> m.getType() == ChatMessage.MessageType.LEAVE).getRoomUsers());
        bob.session().send("/app/chat.sendRoomMessage", Map.of("roomId", roomId, "content", "after leave"));
        await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ERROR);
        bob.session().send("/app/chat.joinRoom", Map.of("roomId", roomId));
        await(bob.privateQueue(), m -> m.getType() == ChatMessage.MessageType.ROOM_JOINED);
        logout(bobIdentity);
        assertEquals(List.of("roomAlice"), await(alice.roomQueue(), m -> m.getType() == ChatMessage.MessageType.LEAVE).getRoomUsers());
        logout(aliceIdentity);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try { rooms.requireRoom(roomId); Thread.sleep(20); }
            catch (IllegalArgumentException removed) { return; }
        }
        fail("Empty room survived logout");
    }
    HttpResponse<String> upload(Identity identity, byte[] bytes, String filename, boolean csrf) throws Exception {
        String boundary = "chatTestBoundary";
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/upload"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (identity != null) { request.header("Cookie", identity.cookie()); if (csrf) request.header("X-CSRF-Token", identity.token()); }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void uploadsRequireLoginAndCsrfAndUseSafeValidatedImageNames() throws Exception {
        var image = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image);
        assertEquals(401, upload(null, image.toByteArray(), "test.png", false).statusCode());
        var identity = login("uploadUser");
        assertEquals(403, upload(identity, image.toByteArray(), "test.png", false).statusCode());
        assertEquals(400, upload(identity, "<script>bad</script>".getBytes(StandardCharsets.UTF_8), "fake.png", true).statusCode());
        var saved = upload(identity, image.toByteArray(), "../../escape.html", true);
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(saved.body().matches("/images/[a-f0-9-]+[.]png"));
        assertEquals(200, get(saved.body(), null).statusCode());
        assertEquals(405, get("/logout", identity.cookie()).statusCode());
    }
    @Test void anonymousSocketCannotConnect() throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        try {
            assertThrows(Exception.class, () -> client.connectAsync("ws://localhost:" + port + "/ws/websocket",
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS));
        } finally { client.stop(); }
    }

    @Test void rawBrokerDestinationsAndOtherUsersQueuesAreForbidden() throws Exception {
        var identity = login("blockedUser");
        for (String destination : List.of("/room/guessed-room", "/user/otherUser/queue/private", "/topic/publicChatRoom")) {
            var client = new WebSocketStompClient(new StandardWebSocketClient());
            client.setMessageConverter(new MappingJackson2MessageConverter());
            client.setMessageConverter(new org.springframework.messaging.converter.ByteArrayMessageConverter());
            var rejection = new CompletableFuture<String>();
            var headers = new WebSocketHttpHeaders(); headers.add("Cookie", identity.cookie());
            try {
                var session = client.connectAsync("ws://localhost:" + port + "/ws/websocket", headers,
                    new StompHeaders(), new StompSessionHandlerAdapter() {
                        @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                        @Override public void handleFrame(StompHeaders headers, Object payload) { rejection.complete(headers.getFirst("message")); }
                        @Override public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) { rejection.completeExceptionally(exception); }
                        @Override public void handleTransportError(StompSession session, Throwable exception) { rejection.complete("Connection closed after forbidden destination"); }
                    }).get(5, TimeUnit.SECONDS);
                if (destination.equals("/topic/publicChatRoom")) session.send(destination, "bypass".getBytes(StandardCharsets.UTF_8));
                else session.subscribe(destination, queue(new LinkedBlockingQueue<>()));
                assertNotNull(rejection.get(5, TimeUnit.SECONDS));
            } finally { client.stop(); }
        }
    }
    @Test void loginRejectsDuplicateAndUnsafeNicknamesAndMissingCsrf() throws Exception {
        login("reservedUser");
        var form = get("/login", null);
        String cookie = cookie(form);
        var missingToken = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
            .header("Cookie", cookie).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("username=anotherUser")).build();
        assertEquals(403, http.send(missingToken, HttpResponse.BodyHandlers.ofString()).statusCode());
        var duplicate = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
            .header("Cookie", cookie).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("username=reservedUser&_csrf=" + token(form.body()))).build();
        var denied = http.send(duplicate, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, denied.statusCode()); assertTrue(denied.body().contains("Username already in use"));
        var unsafe = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
            .header("Cookie", cookie(denied)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("username=%3Cscript%3Ealert%281%29%3C%2Fscript%3E&_csrf=" + token(denied.body()))).build();
        var rejected = http.send(unsafe, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rejected.statusCode()); assertFalse(rejected.body().contains("<script>alert(1)</script>"));
    }
}
