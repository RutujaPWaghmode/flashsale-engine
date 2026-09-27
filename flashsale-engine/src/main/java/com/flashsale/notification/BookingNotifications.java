package com.flashsale.notification;

import com.flashsale.booking.Booking;
import com.flashsale.infra.OutboxService;
import com.flashsale.infra.Topics;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Booking side of the notification flow. Call inside the same MongoDB transaction as the status
 * change: the outbox then guarantees the notification is sent if and only if the change committed.
 * Keyed by userId, so one user's notifications stay in order.
 */
@Component
public class BookingNotifications {

    private final OutboxService outbox;

    public BookingNotifications(OutboxService outbox) {
        this.outbox = outbox;
    }

    public void bookingChanged(Booking booking, NotificationType type, String reason) {
        outbox.enqueue(Topics.NOTIFICATIONS, booking.getUserId(), booking.getId(),
                new NotificationRequested(type, type + ":" + booking.getId(), booking.getUserId(), booking.getEventId(),
                        List.of(booking.getSeatNo()), booking.getId(), booking.getAmount(), reason, null));
    }

    public void reminder(long eventId, String userId, List<String> seatNos) {
        outbox.enqueue(Topics.NOTIFICATIONS, userId, "event-" + eventId,
                new NotificationRequested(NotificationType.EVENT_REMINDER, "EVENT_REMINDER:" + eventId + ":" + userId,
                        userId, eventId, seatNos, null, null, null, null));
    }
}
