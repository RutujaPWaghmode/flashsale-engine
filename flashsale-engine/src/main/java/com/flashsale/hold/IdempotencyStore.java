package com.flashsale.hold;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Idempotency keys, scoped per event and user. The value is "PROCESSING" while the first request
 * runs, then the bookingId it produced, so a retry gets the same answer instead of a second booking.
 * Keys live under the event's {evtN} hash tag, so resetting one event never touches another.
 */
@Component
public class IdempotencyStore {

    public enum State { NEW, IN_PROGRESS, DONE }

    public record Claim(State state, String bookingId) {}

    private static final String PROCESSING = "PROCESSING";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public IdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Claim begin(long eventId, String userId, String idempotencyKey) {
        String key = key(eventId, userId, idempotencyKey);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, PROCESSING, TTL))) {
                return new Claim(State.NEW, null);
            }
            String value = redis.opsForValue().get(key);
            if (value != null) {
                return PROCESSING.equals(value) ? new Claim(State.IN_PROGRESS, null) : new Claim(State.DONE, value);
            }
            // Expired between SET NX and GET: try once more.
        }
        return new Claim(State.IN_PROGRESS, null);
    }

    public void complete(long eventId, String userId, String idempotencyKey, String bookingId) {
        redis.opsForValue().set(key(eventId, userId, idempotencyKey), bookingId, TTL);
    }

    public void abandon(long eventId, String userId, String idempotencyKey) {
        redis.delete(key(eventId, userId, idempotencyKey));
    }

    /** Admin reset of one event: only that event's keys. */
    public void clearEvent(long eventId) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match("idem:{evt" + eventId + "}:*").count(500).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static String key(long eventId, String userId, String idempotencyKey) {
        return "idem:{evt" + eventId + "}:" + userId + ":" + idempotencyKey;
    }

    /** The same identity as stored on the booking document (unique index), so it survives Redis data loss. */
    public static String bookingKey(long eventId, String userId, String idempotencyKey) {
        return userId + ":" + eventId + ":" + idempotencyKey;
    }
}
