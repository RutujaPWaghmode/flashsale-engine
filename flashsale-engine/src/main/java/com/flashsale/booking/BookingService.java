package com.flashsale.booking;

import com.flashsale.common.ConflictException;
import com.flashsale.common.NotFoundException;
import com.flashsale.hold.IdempotencyStore;
import com.flashsale.hold.SeatHoldService;
import com.flashsale.infra.FlashsaleMetrics;
import com.flashsale.infra.OutboxService;
import com.flashsale.infra.Topics;
import com.flashsale.notify.SeatUpdateNotifier;
import com.flashsale.payment.PaymentOutcome;
import com.flashsale.payment.PaymentRequested;
import com.flashsale.seat.EventCatalog;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
public class BookingService {

    private final EventCatalog catalog;
    private final IdempotencyStore idempotency;
    private final SeatHoldService seatHolds;
    private final BookingRepository bookings;
    private final BookingTransitions transitions;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final SeatUpdateNotifier notifier;
    private final FlashsaleMetrics metrics;
    private final BookingProperties props;

    public BookingService(EventCatalog catalog, IdempotencyStore idempotency, SeatHoldService seatHolds,
                          BookingRepository bookings, BookingTransitions transitions, OutboxService outbox,
                          TransactionTemplate tx, SeatUpdateNotifier notifier, FlashsaleMetrics metrics,
                          BookingProperties props) {
        this.catalog = catalog;
        this.idempotency = idempotency;
        this.seatHolds = seatHolds;
        this.bookings = bookings;
        this.transitions = transitions;
        this.outbox = outbox;
        this.tx = tx;
        this.notifier = notifier;
        this.metrics = metrics;
        this.props = props;
    }

    /**
     * The hot path: seat check in memory, idempotency and hold in Redis. Only winners touch MongoDB
     * (one insert), so thousands of losing requests never reach the database.
     */
    public HoldOutcome hold(String userId, String idempotencyKey, long eventId, String seatNo) {
        EventCatalog.Entry entry = catalog.find(eventId)
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
        if (!entry.seatSet().contains(seatNo)) {
            throw new NotFoundException("Seat " + seatNo + " not found");
        }
        if (!entry.event().startTime().isAfter(Instant.now())) {
            throw new ConflictException("EVENT_STARTED", "Sales for " + entry.event().name() + " have closed");
        }

        IdempotencyStore.Claim claim = idempotency.begin(eventId, userId, idempotencyKey);
        if (claim.state() == IdempotencyStore.State.DONE) {
            var prior = bookings.findById(claim.bookingId());
            if (prior.isPresent()) {
                return new HoldOutcome(HoldOutcome.Status.DUPLICATE, prior.get().getSeatNo(), BookingView.of(prior.get()));
            }
            idempotency.abandon(eventId, userId, idempotencyKey);   // booking was wiped by an admin reset
            claim = idempotency.begin(eventId, userId, idempotencyKey);
        }
        if (claim.state() != IdempotencyStore.State.NEW) {
            metrics.rejected("IN_PROGRESS");
            return new HoldOutcome(HoldOutcome.Status.IN_PROGRESS, seatNo, null);
        }

        SeatHoldService.HoldResult result;
        try {
            result = seatHolds.tryHold(eventId, seatNo, userId);
        } catch (RuntimeException e) {
            idempotency.abandon(eventId, userId, idempotencyKey);
            throw e;
        }
        if (result != SeatHoldService.HoldResult.HELD) {
            idempotency.abandon(eventId, userId, idempotencyKey);
            metrics.rejected(result.name());
            return new HoldOutcome(result == SeatHoldService.HoldResult.HOLD_LIMIT
                    ? HoldOutcome.Status.HOLD_LIMIT : HoldOutcome.Status.SEAT_TAKEN, seatNo, null);
        }

        Booking booking;
        try {
            booking = bookings.insert(Booking.held(userId, IdempotencyStore.bookingKey(eventId, userId, idempotencyKey), eventId, seatNo,
                    entry.event().price(), Instant.now().plus(props.holdTtl())));
        } catch (DuplicateKeyException redisLostTheKey) {
            // Redis forgot the idempotency key (restart) but MongoDB remembers the booking.
            seatHolds.release(eventId, seatNo, userId);
            Booking prior = bookings.findByIdempotencyKey(IdempotencyStore.bookingKey(eventId, userId, idempotencyKey)).orElseThrow();
            idempotency.complete(eventId, userId, idempotencyKey, prior.getId());
            return new HoldOutcome(HoldOutcome.Status.DUPLICATE, prior.getSeatNo(), BookingView.of(prior));
        } catch (RuntimeException mongoDown) {
            seatHolds.release(eventId, seatNo, userId);
            idempotency.abandon(eventId, userId, idempotencyKey);
            throw mongoDown;
        }

        idempotency.complete(eventId, userId, idempotencyKey, booking.getId());
        metrics.held();
        notifier.seatChanged(eventId, seatNo);
        return new HoldOutcome(HoldOutcome.Status.HELD, seatNo, BookingView.of(booking));
    }

    /**
     * Starts the payment saga. The status change and the PaymentRequested event are written in one
     * MongoDB transaction (outbox pattern); the outbox publisher forwards the event to Kafka.
     */
    public BookingView pay(String userId, String bookingId, PaymentOutcome outcome) {
        Booking booking = owned(userId, bookingId);
        switch (booking.getStatus()) {
            case HELD -> { }
            case EXPIRED -> throw new ConflictException("HOLD_EXPIRED", "The hold on seat " + booking.getSeatNo() + " expired");
            default -> { return BookingView.of(booking); }   // already paying or finished: idempotent
        }

        if (!seatHolds.extend(booking.getEventId(), booking.getSeatNo(), userId, props.paymentHoldExtension())) {
            if (transitions.transition(bookingId, BookingStatus.HELD, BookingStatus.EXPIRED)) {
                metrics.expired();
            }
            throw new ConflictException("HOLD_EXPIRED", "The hold on seat " + booking.getSeatNo() + " expired");
        }

        Instant holdExpiresAt = Instant.now().plus(props.paymentHoldExtension());
        tx.executeWithoutResult(status -> {
            boolean moved = transitions.transition(bookingId, BookingStatus.HELD, BookingStatus.PAYMENT_PENDING,
                    new Update().max("holdExpiresAt", holdExpiresAt));
            if (!moved) {
                throw new ConflictException("NOT_HELD", "Booking is no longer held");
            }
            outbox.enqueue(Topics.PAYMENT_REQUESTED, String.valueOf(booking.getEventId()), bookingId,
                    PaymentRequested.pay(bookingId, booking.getEventId(), booking.getSeatNo(), userId,
                            booking.getAmount(), outcome != null ? outcome : PaymentOutcome.SUCCESS));
        });
        return BookingView.of(bookings.findById(bookingId).orElseThrow());
    }

    /** The user gives the seat back before paying. */
    public BookingView cancel(String userId, String bookingId) {
        Booking booking = owned(userId, bookingId);
        if (booking.getStatus() != BookingStatus.HELD) {
            throw new ConflictException("NOT_HELD", "Only a held booking can be released");
        }
        if (transitions.transition(bookingId, BookingStatus.HELD, BookingStatus.CANCELLED)) {
            seatHolds.release(booking.getEventId(), booking.getSeatNo(), userId);
            metrics.cancelled();
            notifier.seatChanged(booking.getEventId(), booking.getSeatNo());
        }
        return BookingView.of(bookings.findById(bookingId).orElseThrow());
    }

    public BookingView get(String userId, String bookingId) {
        return BookingView.of(owned(userId, bookingId));
    }

    public List<BookingView> mine(String userId, long eventId) {
        return bookings.findByUserIdAndEventIdOrderByCreatedAtDesc(userId, eventId, Limit.of(50))
                .stream().map(BookingView::of).toList();
    }

    private Booking owned(String userId, String bookingId) {
        return bookings.findById(bookingId)
                .filter(b -> b.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Booking " + bookingId + " not found"));
    }
}
