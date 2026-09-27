package com.flashsale.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Applies state-machine transitions as compare-and-set updates: the write only happens if the
 * booking is still in the expected state, so two racing handlers can never both win.
 *
 * Bookings that end without a sale or a payment (FAILED, EXPIRED, CANCELLED) get a {@code purgeAt}
 * date and are deleted by a TTL index. CONFIRMED and REFUNDED bookings are kept: they are money.
 */
@Component
public class BookingTransitions {

    private static final Set<BookingStatus> PURGEABLE = EnumSet.of(BookingStatus.FAILED, BookingStatus.EXPIRED, BookingStatus.CANCELLED);

    private final MongoTemplate mongo;
    private final Duration abandonedRetention;

    public BookingTransitions(MongoTemplate mongo,
                              @Value("${app.retention.abandoned-bookings:30d}") Duration abandonedRetention) {
        this.mongo = mongo;
        this.abandonedRetention = abandonedRetention;
    }

    /** @return true if this call made the transition, false if the booking was no longer in {@code from}. */
    public boolean transition(String bookingId, BookingStatus from, BookingStatus to, Update extra) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("Illegal booking transition " + from + " -> " + to);
        }
        Update update = extra != null ? extra : new Update();
        Instant now = Instant.now();
        update.set("status", to).set("updatedAt", now);
        if (PURGEABLE.contains(to)) {
            update.set("purgeAt", now.plus(abandonedRetention));
        } else {
            update.unset("purgeAt");   // e.g. EXPIRED -> REFUNDED: keep it
        }
        return mongo.updateFirst(query(where("id").is(bookingId).and("status").is(from)), update, Booking.class)
                .getModifiedCount() == 1;
    }

    public boolean transition(String bookingId, BookingStatus from, BookingStatus to) {
        return transition(bookingId, from, to, null);
    }
}
