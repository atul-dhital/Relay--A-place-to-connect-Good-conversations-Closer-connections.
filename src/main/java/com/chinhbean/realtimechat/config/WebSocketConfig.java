package com.chinhbean.realtimechat.config;
import com.chinhbean.realtimechat.interceptor.HttpHandshakeInterceptor;
import com.chinhbean.realtimechat.service.ChatSessions;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import java.util.Set;
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final HttpHandshakeInterceptor handshake;
    private final ChatSessions sessions;
    private static final Set<String> SEND_DESTINATIONS = Set.of("/app/chat.sendMessage", "/app/chat.addUser",
        "/app/chat.sendPrivateMessage", "/app/chat.createPrivateRoom", "/app/chat.joinRoom",
        "/app/chat.leaveRoom", "/app/chat.sendRoomMessage");
    private static final Set<String> SUBSCRIPTIONS = Set.of("/topic/publicChatRoom", "/user/queue/private", "/user/queue/room");
    public WebSocketConfig(HttpHandshakeInterceptor handshake, ChatSessions sessions) {
        this.handshake = handshake; this.sessions = sessions;
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws").addInterceptors(handshake).withSockJS();
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (headers == null || headers.getCommand() == null || headers.getCommand() == StompCommand.DISCONNECT) return message;
                var attrs = headers.getSessionAttributes();
                String username = attrs == null ? null : (String) attrs.get("username");
                String sessionId = attrs == null ? null : (String) attrs.get("httpSessionId");
                if (!sessions.active(username, sessionId)) {
                    sessions.reject(headers.getSessionId());
                    throw new MessagingException("Login required.");
                }
                if (headers.getCommand() == StompCommand.CONNECT || headers.getCommand() == StompCommand.STOMP) headers.setUser(() -> username);
                if (headers.getCommand() == StompCommand.SEND && !SEND_DESTINATIONS.contains(headers.getDestination())) {
                    sessions.reject(headers.getSessionId());
                    throw new MessagingException("Sending to this destination is forbidden.");
                }
                if (headers.getCommand() == StompCommand.SUBSCRIBE && !SUBSCRIPTIONS.contains(headers.getDestination())) {
                    sessions.reject(headers.getSessionId());
                    throw new MessagingException("Subscribing to this destination is forbidden.");
                }
                return message;
            }
        });
    }
    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(32 * 1024);
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.register(session);
                if (session.isOpen()) super.afterConnectionEstablished(session);
            }
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.unregister(session.getId()); super.afterConnectionClosed(session, status);
            }
        });
    }
}
