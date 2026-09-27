package com.flashsale.admin;

import com.flashsale.booking.Booking;
import com.flashsale.booking.BookingRepository;
import com.flashsale.common.NotFoundException;
import com.flashsale.hold.IdempotencyStore;
import com.flashsale.hold.SeatHoldService;
import com.flashsale.notification.NotificationRecordRepository;
import com.flashsale.notify.SeatUpdateNotifier;
import com.flashsale.payment.PaymentRepository;
import com.flashsale.seat.EventCatalog;
import com.flashsale.seat.EventRepository;
import com.flashsale.seat.Seat;
import com.flashsale.seat.SeatStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/** Puts an event back to "all seats available". For demos and between load-test runs. */
@Service
public class EventResetService {

    private final EventRepository events;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final MongoTemplate mongo;
    private final SeatHoldService seatHolds;
    private final IdempotencyStore idempotency;
    private final EventCatalog catalog;
    private final SeatUpdateNotifier notifier;
    private final NotificationRecordRepository notificationLog;

    public EventResetService(EventRepository events, BookingRepository bookings, PaymentRepository payments,
                             MongoTemplate mongo, SeatHoldService seatHolds, IdempotencyStore idempotency,
                             EventCatalog catalog, SeatUpdateNotifier notifier,
                             NotificationRecordRepository notificationLog) {
        this.events = events;
        this.bookings = bookings;
        this.payments = payments;
        this.mongo = mongo;
        this.seatHolds = seatHolds;
        this.idempotency = idempotency;
        this.catalog = catalog;
        this.notifier = notifier;
        this.notificationLog = notificationLog;
    }

    public void reset(long eventId) {
        if (!events.existsById(eventId)) {
            throw new NotFoundException("Event " + eventId + " not found");
        }
        List<String> bookingIds = bookings.findByEventId(eventId).stream().map(Booking::getId).toList();
        if (!bookingIds.isEmpty()) {
            payments.deleteByBookingIdIn(bookingIds);
        }
        bookings.deleteByEventId(eventId);
        mongo.updateMulti(query(where("eventId").is(eventId)),
                new Update().set("status", SeatStatus.AVAILABLE).set("version", 0L), Seat.class);
        mongo.remove(query(where("_id").is(eventId)), "event_reminders");
        notificationLog.deleteByEventId(eventId);
        seatHolds.clearEvent(eventId);
        idempotency.clearEvent(eventId);
        catalog.invalidate(eventId);
        notifier.eventReset(eventId);
    }
}
