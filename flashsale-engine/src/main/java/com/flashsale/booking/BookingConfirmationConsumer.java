package com.flashsale.booking;

import com.flashsale.hold.SeatHoldService;
import com.flashsale.infra.FlashsaleMetrics;
import com.flashsale.infra.OutboxService;
import com.flashsale.infra.Topics;
import com.flashsale.notification.BookingNotifications;
import com.flashsale.notification.NotificationType;
import com.flashsale.notify.SeatUpdateNotifier;
import com.flashsale.payment.PaymentRequested;
import com.flashsale.payment.PaymentResult;
import com.flashsale.seat.Seat;
import com.flashsale.seat.SeatStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Second half of the saga. Kafka delivers at least once, so every branch is idempotent:
 * a redelivered message finds the booking already in a final state and does nothing.
 */
@Component
public class BookingConfirmationConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmationConsumer.class);

    private final BookingRepository bookings;
    private final BookingTransitions transitions;
    private final SeatHoldService seatHolds;
    private final OutboxService outbox;
    private final MongoTemplate mongo;
    private final TransactionTemplate tx;
    private final SeatUpdateNotifier notifier;
    private final FlashsaleMetrics metrics;
    private final JsonMapper json;
    private final BookingNotifications notifications;

    public BookingConfirmationConsumer(BookingRepository bookings, BookingTransitions transitions,
                                       SeatHoldService seatHolds, OutboxService outbox,
                                       MongoTemplate mongo, TransactionTemplate tx, SeatUpdateNotifier notifier,
                                       FlashsaleMetrics metrics, JsonMapper json, BookingNotifications notifications) {
        this.bookings = bookings;
        this.transitions = transitions;
        this.seatHolds = seatHolds;
        this.outbox = outbox;
        this.mongo = mongo;
        this.tx = tx;
        this.notifier = notifier;
        this.metrics = metrics;
        this.json = json;
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.PAYMENT_SUCCEEDED, groupId = "booking-service")
    public void onPaymentSucceeded(String message) {
        PaymentResult result = json.readValue(message, PaymentResult.class);
        Booking booking = load(result.bookingId());

        switch (booking.getStatus()) {
            case PAYMENT_PENDING -> confirmOrRefund(booking, result);
            case EXPIRED -> refund(booking, BookingStatus.EXPIRED, "HOLD_EXPIRED", "Hold expired before payment completed");
            default -> log.debug("Booking {} already {}, ignoring redelivery", booking.getId(), booking.getStatus());
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED, groupId = "booking-service")
    public void onPaymentFailed(String message) {
        PaymentResult result = json.readValue(message, PaymentResult.class);
        Booking booking = load(result.bookingId());

        boolean moved = Boolean.TRUE.equals(tx.execute(status -> {
            boolean ok = transitions.transition(booking.getId(), BookingStatus.PAYMENT_PENDING, BookingStatus.FAILED,
                    new Update().set("failureReason", result.reason()));
            if (ok) notifications.bookingChanged(booking, NotificationType.PAYMENT_FAILED, result.reason());
            return ok;
        }));
        // A late failure for an expired hold needs no compensation: nothing was charged or held.
        if (moved) {
            seatHolds.release(booking.getEventId(), booking.getSeatNo(), booking.getUserId());   // compensation
            metrics.failed();
            notifier.seatChanged(booking.getEventId(), booking.getSeatNo());
        }
    }

    private void confirmOrRefund(Booking booking, PaymentResult result) {
        long eventId = booking.getEventId();
        String seatNo = booking.getSeatNo();

        if (!seatHolds.confirm(eventId, seatNo, booking.getUserId())) {
            refund(booking, BookingStatus.PAYMENT_PENDING, "HOLD_LOST", "Seat hold was lost before confirmation");
            return;
        }
        try {
            Boolean confirmed = tx.execute(status -> {
                boolean moved = transitions.transition(booking.getId(), BookingStatus.PAYMENT_PENDING,
                        BookingStatus.CONFIRMED, new Update().set("paymentId", result.paymentId()));
                if (moved) {
                    mongo.updateFirst(query(where("eventId").is(eventId).and("seatNo").is(seatNo)),
                            new Update().set("status", SeatStatus.BOOKED).inc("version", 1), Seat.class);
                    notifications.bookingChanged(booking, NotificationType.BOOKING_CONFIRMED, null);
                }
                return moved;
            });
            if (Boolean.TRUE.equals(confirmed)) {
                metrics.confirmed();
                notifier.seatChanged(eventId, seatNo);
                return;
            }
            // Lost a race with the expiry sweeper: undo the Redis sale and refund.
            Booking now = load(booking.getId());
            if (now.getStatus() == BookingStatus.EXPIRED) {
                seatHolds.release(eventId, seatNo, booking.getUserId());
                refund(now, BookingStatus.EXPIRED, "HOLD_EXPIRED", "Hold expired before payment completed");
            }
        } catch (DuplicateKeyException alreadySold) {
            // The partial unique index saw another CONFIRMED booking for this seat. Only possible after
            // Redis lost data; the database is the last line of defence. Reconciliation repairs Redis.
            metrics.oversellBlocked();
            log.warn("Oversell blocked by unique index: event {} seat {} booking {}", eventId, seatNo, booking.getId());
            refund(booking, BookingStatus.PAYMENT_PENDING, "SEAT_SOLD", "Seat was already sold");
        }
    }

    private void refund(Booking booking, BookingStatus from, String reasonCode, String reason) {
        // Status change, refund command and email in one transaction: all happen, or none.
        boolean moved = Boolean.TRUE.equals(tx.execute(status -> {
            boolean ok = transitions.transition(booking.getId(), from, BookingStatus.REFUNDED,
                    new Update().set("failureReason", reason));
            if (ok) {
                outbox.enqueue(Topics.PAYMENT_REQUESTED, String.valueOf(booking.getEventId()), booking.getId(),
                        PaymentRequested.refund(booking.getId(), booking.getEventId(), booking.getSeatNo(),
                                booking.getUserId(), booking.getAmount(), reason));
                notifications.bookingChanged(booking, NotificationType.BOOKING_REFUNDED, reason);
            }
            return ok;
        }));
        if (moved) {
            metrics.refunded(reasonCode);
            log.info("Refunded booking {}: {}", booking.getId(), reason);
        }
    }

    private Booking load(String bookingId) {
        // Unknown booking: throw, so the error handler retries and then dead-letters the message.
        return bookings.findById(bookingId)
                .orElseThrow(() -> new IllegalStateException("Unknown booking " + bookingId));
    }
}
