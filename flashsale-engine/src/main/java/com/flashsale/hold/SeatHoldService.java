package com.flashsale.hold;

import com.flashsale.booking.BookingProperties;
import com.flashsale.seat.SeatStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The hot path. A seat key holds the userId while the seat is held (with a TTL) and
 * "SOLD:userId" (no TTL) once the booking is confirmed.
 */
@Service
public class SeatHoldService {

    public enum HoldResult { HELD, SEAT_TAKEN, HOLD_LIMIT }

    private static final String SOLD_PREFIX = "SOLD:";

    private final StringRedisTemplate redis;
    private final BookingProperties props;
    private final DefaultRedisScript<Long> holdScript = script("hold_seat");
    private final DefaultRedisScript<Long> releaseScript = script("release_seat");
    private final DefaultRedisScript<Long> extendScript = script("extend_hold");
    private final DefaultRedisScript<Long> confirmScript = script("confirm_seat");

    public SeatHoldService(StringRedisTemplate redis, BookingProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public HoldResult tryHold(long eventId, String seatNo, String userId) {
        Long result = redis.execute(holdScript,
                List.of(SeatKeys.seat(eventId, seatNo), SeatKeys.userHolds(eventId, userId)),
                userId,
                String.valueOf(props.holdTtl().toSeconds()),
                String.valueOf(props.maxSeatsPerUser()));
        if (Long.valueOf(1L).equals(result)) return HoldResult.HELD;
        if (Long.valueOf(-1L).equals(result)) return HoldResult.HOLD_LIMIT;
        return HoldResult.SEAT_TAKEN;
    }

    public boolean release(long eventId, String seatNo, String userId) {
        Long result = redis.execute(releaseScript,
                List.of(SeatKeys.seat(eventId, seatNo), SeatKeys.userHolds(eventId, userId)), userId);
        return Long.valueOf(1L).equals(result);
    }

    /** Keeps the hold alive for at least {@code minRemaining} while payment is in flight. */
    public boolean extend(long eventId, String seatNo, String userId, Duration minRemaining) {
        Long result = redis.execute(extendScript,
                List.of(SeatKeys.seat(eventId, seatNo)), userId, String.valueOf(minRemaining.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    /** Marks the seat as sold to this user. False means the hold was lost before payment finished. */
    public boolean confirm(long eventId, String seatNo, String userId) {
        Long result = redis.execute(confirmScript, List.of(SeatKeys.seat(eventId, seatNo)), userId);
        return Long.valueOf(1L).equals(result);
    }

    /** Held or sold by this user. */
    public boolean ownedBy(long eventId, String seatNo, String userId) {
        String owner = redis.opsForValue().get(SeatKeys.seat(eventId, seatNo));
        return userId.equals(owner) || (SOLD_PREFIX + userId).equals(owner);
    }

    /** Reconciliation: restore a sale that MongoDB knows about but Redis lost. */
    public void markSold(long eventId, String seatNo, String userId) {
        redis.opsForValue().set(SeatKeys.seat(eventId, seatNo), SOLD_PREFIX + userId);
    }

    /** Raw key values for the given seats, in order (null = free). */
    public List<String> rawValues(long eventId, List<String> seatNos) {
        List<String> keys = seatNos.stream().map(s -> SeatKeys.seat(eventId, s)).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        return values != null ? values : new ArrayList<>(java.util.Collections.nCopies(seatNos.size(), null));
    }

    public static SeatStatus statusOf(String rawValue) {
        if (rawValue == null) return SeatStatus.AVAILABLE;
        return rawValue.startsWith(SOLD_PREFIX) ? SeatStatus.BOOKED : SeatStatus.HELD;
    }

    public static String soldTo(String rawValue) {
        return rawValue != null && rawValue.startsWith(SOLD_PREFIX) ? rawValue.substring(SOLD_PREFIX.length()) : null;
    }

    /** Admin reset: deletes every hold, sale and per-user set of the event. */
    public void clearEvent(long eventId) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(SeatKeys.eventPattern(eventId)).count(500).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
