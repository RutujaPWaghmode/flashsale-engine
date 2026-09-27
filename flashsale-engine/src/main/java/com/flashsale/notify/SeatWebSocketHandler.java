package com.flashsale.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** ws://host/ws/events/{eventId}: server-to-client seat updates. Clients never send anything. */
@Component
public class SeatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SeatWebSocketHandler.class);
    private static final String EVENT_ID = "eventId";

    private final Map<Long, Map<String, WebSocketSession>> sessionsByEvent = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Long eventId = eventIdFrom(session.getUri());
        if (eventId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        session.getAttributes().put(EVENT_ID, eventId);
        // The decorator serializes concurrent sends and drops slow clients instead of blocking.
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024);
        sessionsByEvent.computeIfAbsent(eventId, id -> new ConcurrentHashMap<>()).put(session.getId(), safe);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object eventId = session.getAttributes().get(EVENT_ID);
        if (eventId != null) {
            Map<String, WebSocketSession> sessions = sessionsByEvent.get((Long) eventId);
            if (sessions != null) {
                sessions.remove(session.getId());
            }
        }
    }

    public void broadcast(long eventId, String payload) {
        Map<String, WebSocketSession> sessions = sessionsByEvent.get(eventId);
        if (sessions == null) {
            return;
        }
        TextMessage message = new TextMessage(payload);
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping WebSocket session {}: {}", session.getId(), e.getMessage());
                sessions.remove(session.getId());
            }
        });
    }

    private static Long eventIdFrom(URI uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        try {
            return Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
