package com.flashsale.notification;

import com.flashsale.booking.Booking;
import com.flashsale.booking.BookingRepository;
import com.flashsale.booking.BookingStatus;
import com.flashsale.common.NotFoundException;
import com.flashsale.infra.JobLock;
import com.flashsale.seat.Event;
import com.flashsale.seat.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Once an event is within {@code app.notifications.reminder-before} of starting, queues one
 * reminder per user with a confirmed booking. The marker insert (unique per event) and the
 * reminders share a transaction, so each event is reminded exactly once, even with several
 * app instances running this job.
 */
@Component
public class EventReminderJob {

    @Document("event_reminders")
    record ReminderMarker(@Id long eventId, Instant queuedAt) {}

    private static final Logger log = LoggerFactory.getLogger(EventReminderJob.class);

    private final EventRepository events;
    private final BookingRepository bookings;
    private final BookingNotifications notifications;
    private final MongoTemplate mongo;
    private final TransactionTemplate tx;
    private final Duration reminderBefore;
    private final JobLock lock;

    public EventReminderJob(EventRepository events, BookingRepository bookings, BookingNotifications notifications,
                            MongoTemplate mongo, TransactionTemplate tx, JobLock lock,
                            @Value("${app.notifications.reminder-before:24h}") Duration reminderBefore) {
        this.events = events;
        this.bookings = bookings;
        this.notifications = notifications;
        this.mongo = mongo;
        this.tx = tx;
        this.reminderBefore = reminderBefore;
        this.lock = lock;
    }

    @Scheduled(initialDelayString = "${app.notifications.reminder-initial-delay-ms:15000}",
               fixedDelayString = "${app.notifications.reminder-check-ms:60000}")
    public void sendDueReminders() {
        lock.runExclusively("event-reminders", Duration.ofMinutes(5), () -> {
            Instant now = Instant.now();
            for (Event event : events.findByStartTimeBetweenOrderByStartTime(now, now.plus(reminderBefore))) {
                if (!mongo.exists(query(where("_id").is(event.id())), ReminderMarker.class)) {
                    remind(event.id());
                }
            }
        });
    }

    /** @return number of reminders queued (0 if this event was already reminded) */
    public int remind(long eventId) {
        if (!events.existsById(eventId)) {
            throw new NotFoundException("Event " + eventId + " not found");
        }
        Map<String, List<String>> seatsByUser = bookings.findByEventIdAndStatus(eventId, BookingStatus.CONFIRMED).stream()
                .collect(Collectors.groupingBy(Booking::getUserId, TreeMap::new,
                        Collectors.mapping(Booking::getSeatNo, Collectors.toList())));
        try {
            tx.executeWithoutResult(status -> {
                mongo.insert(new ReminderMarker(eventId, Instant.now()));
                seatsByUser.forEach((userId, seats) -> notifications.reminder(eventId, userId, seats.stream().sorted().toList()));
            });
        } catch (DuplicateKeyException alreadyReminded) {
            return 0;
        }
        log.info("Queued {} reminder(s) for event {}", seatsByUser.size(), eventId);
        return seatsByUser.size();
    }
}
