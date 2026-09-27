package com.flashsale.notify;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSocket
public class NotificationConfig implements WebSocketConfigurer {

    private final SeatWebSocketHandler handler;

    public NotificationConfig(SeatWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/events/*");   // same-origin only (Spring's default)
    }

    /** Redis pub/sub -> local WebSocket sessions. */
    @Bean
    RedisMessageListenerContainer seatUpdateListener(RedisConnectionFactory connectionFactory, JsonMapper json) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            SeatUpdate update = json.readValue(payload, SeatUpdate.class);
            handler.broadcast(update.eventId(), payload);
        }, new ChannelTopic(SeatUpdateNotifier.CHANNEL));
        return container;
    }
}
