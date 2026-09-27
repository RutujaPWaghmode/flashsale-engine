package com.flashsale.booking;

import com.flashsale.hold.SeatHoldService;
import com.flashsale.infra.FlashsaleMetrics;
import com.flashsale.infra.JobLock;
import com.flashsale.notification.BookingNotifications;
import com.flashsale.notification.NotificationType;
import com.flashsale.notify.SeatUpdateNotifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Redis frees an abandoned seat by itself (TTL). This job only brings the booking documents in
 * line, marking them EXPIRED, and tells browsers the seat is free again.
 */
@Component
public class ExpiredHoldSweeper {

    private final MongoTemplate mongo;
    private final BookingTransitions transitions;
    private final SeatHoldService seatHolds;
    private final SeatUpdateNotifier notifier;
    private final FlashsaleMetrics metrics;
    private final BookingNotifications notifications;
    private final TransactionTemplate tx;
    private final JobLock lock;

    public ExpiredHoldSweeper(MongoTemplate mongo, BookingTransitions transitions, SeatHoldService seatHolds,
                              SeatUpdateNotifier notifier, FlashsaleMetrics metrics,
                              BookingNotifications notifications, TransactionTemplate tx, JobLock lock) {
        this.mongo = mongo;
        this.transitions = transitions;
        this.seatHolds = seatHolds;
        this.notifier = notifier;
        this.metrics = metrics;
        this.notifications = notifications;
        this.tx = tx;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${app.booking.sweep-interval-ms:5000}")
    public void scheduledSweep() {
        lock.runExclusively("expired-hold-sweeper", Duration.ofSeconds(30), this::sweep);
    }

    public void sweep() {
        List<Booking> stale = mongo.find(
                query(where("status").in(BookingStatus.HELD, BookingStatus.PAYMENT_PENDING)
                        .and("holdExpiresAt").lt(Instant.now())).limit(500),
                Booking.class);
        for (Booking b : stale) {
            if (seatHolds.ownedBy(b.getEventId(), b.getSeatNo(), b.getUserId())) {
                continue;   // Redis still says it is theirs (hold extended, or confirmation in flight)
            }
            boolean moved = Boolean.TRUE.equals(tx.execute(status -> {
                boolean ok = transitions.transition(b.getId(), b.getStatus(), BookingStatus.EXPIRED);
                if (ok) notifications.bookingChanged(b, NotificationType.HOLD_EXPIRED, null);
                return ok;
            }));
            if (moved) {
                metrics.expired();
                notifier.seatChanged(b.getEventId(), b.getSeatNo());
            }
        }
    }
}
