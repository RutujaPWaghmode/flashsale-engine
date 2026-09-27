package com.flashsale.notify;

import com.flashsale.seat.SeatQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes seat changes on a Redis pub/sub channel. Every app instance subscribes and pushes to
 * its own WebSocket clients, so updates reach users no matter which instance they are connected to.
 * Best effort: a lost notification only means the page refreshes that seat a bit later.
 */
@Component
public class SeatUpdateNotifier {

    public static final String CHANNEL = "seat-updates";
    private static final Logger log = LoggerFactory.getLogger(SeatUpdateNotifier.class);

    private final StringRedisTemplate redis;
    private final SeatQueryService seatQuery;
    private final JsonMapper json;

    public SeatUpdateNotifier(StringRedisTemplate redis, SeatQueryService seatQuery, JsonMapper json) {
        this.redis = redis;
        this.seatQuery = seatQuery;
        this.json = json;
    }

    /** Broadcasts the seat's current status (read fresh, so late or reordered calls stay correct). */
    public void seatChanged(long eventId, String seatNo) {
        try {
            publish(SeatUpdate.seat(eventId, seatNo, seatQuery.current(eventId, seatNo)));
        } catch (RuntimeException e) {
            log.debug("Seat update for {}/{} not published: {}", eventId, seatNo, e.getMessage());
        }
    }

    public void eventReset(long eventId) {
        try {
            publish(SeatUpdate.reset(eventId));
        } catch (RuntimeException e) {
            log.debug("Reset notification for {} not published: {}", eventId, e.getMessage());
        }
    }

    private void publish(SeatUpdate update) {
        redis.convertAndSend(CHANNEL, json.writeValueAsString(update));
    }
}
