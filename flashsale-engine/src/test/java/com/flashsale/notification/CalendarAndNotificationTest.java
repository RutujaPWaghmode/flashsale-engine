package com.flashsale.notification;

import com.flashsale.admin.EventAdminService;
import com.flashsale.booking.BookingRepository;
import com.flashsale.booking.BookingService;
import com.flashsale.booking.BookingStatus;
import com.flashsale.booking.HoldOutcome;
import com.flashsale.common.ConflictException;
import com.flashsale.payment.PaymentOutcome;
import com.flashsale.seat.DataSeeder;
import com.flashsale.seat.Event;
import com.flashsale.seat.EventCatalog;
import com.flashsale.seat.EventController;
import com.flashsale.seat.EventIdSequence;
import com.flashsale.security.User;
import com.flashsale.security.UserRepository;
import com.flashsale.support.IntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Event calendar + the Kafka-driven email pipeline, end to end (emails captured by the mock sender). */
class CalendarAndNotificationTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookings;
    @Autowired UserRepository users;
    @Autowired EventAdminService eventAdmin;
    @Autowired EventController eventController;
    @Autowired EventCatalog catalog;
    @Autowired EventIdSequence ids;
    @Autowired DataSeeder seeder;
    @Autowired EventReminderJob reminders;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRecordRepository notificationLog;
    @Autowired JsonMapper json;

    // ---------- calendar ----------

    @Test
    void adminCreatesEvent_withGeneratedSeats_andItAppearsInTheCalendarRange() {
        Instant start = Instant.now().plus(Duration.ofDays(3));
        Event event = eventAdmin.create(new EventAdminService.CreateEventRequest(
                "Test Gig", "Test Hall", start, 3, 4, new BigDecimal("250")));

        assertThat(catalog.find(event.id()).orElseThrow().seatNos())
                .hasSize(12).startsWith("A01", "A02").endsWith("C04");
        assertThat(eventController.list(start.minusSeconds(60), start.plusSeconds(60)))
                .extracting(EventController.EventView::id).contains(event.id());
        assertThat(eventController.list(start.plusSeconds(60), null))
                .extracting(EventController.EventView::id).doesNotContain(event.id());
        assertThat(eventController.get(event.id()).price()).isEqualByComparingTo("250.00");
    }

    @Test
    void startedEvent_rejectsHolds() {
        long id = ids.next();
        seeder.create(new Event(id, "Yesterday's Show", "Hall", Instant.now().minusSeconds(3600), 4, BigDecimal.TEN), 1, 4);

        assertThatThrownBy(() -> bookingService.hold("alice", UUID.randomUUID().toString(), id, "A01"))
                .isInstanceOf(ConflictException.class);
        assertThat(eventController.get(id).onSale()).isFalse();
    }

    // ---------- email ----------

    @Test
    void confirmedBooking_sendsEmailWithCalendarInvite() throws Exception {
        String email = user("mia");
        String id = hold("mia", "A01").booking().id();

        bookingService.pay("mia", id, PaymentOutcome.SUCCESS);

        MimeMessage mail = awaitMail(email, "is confirmed");
        String raw = raw(mail);
        assertThat(raw).contains("text/calendar").contains("ticket.ics");
        assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(notificationLog.findById("BOOKING_CONFIRMED:" + id)).get()
                .extracting(NotificationRecord::status).isEqualTo(NotificationRecord.Status.SENT);
    }

    @Test
    void declinedPayment_sendsDeclineEmail() throws Exception {
        String email = user("noah");
        String id = hold("noah", "A02").booking().id();

        bookingService.pay("noah", id, PaymentOutcome.DECLINE);

        assertThat(raw(awaitMail(email, "Payment declined"))).contains("released");
    }

    @Test
    void redeliveredNotification_sendsOnlyOneEmail() throws Exception {
        String email = user("olga");
        NotificationRequested n = new NotificationRequested(NotificationType.HOLD_EXPIRED,
                "HOLD_EXPIRED:test-" + UUID.randomUUID(), "olga", EVENT_ID, List.of("B03"), "b-1", null, null, null);
        String message = json.writeValueAsString(n);

        notificationService.onNotification(message);
        notificationService.onNotification(message);   // Kafka redelivery

        assertThat(sentTo(email)).hasSize(1);
    }

    @Test
    void userWithoutEmail_isSkippedNotFailed() throws Exception {
        String key = "HOLD_EXPIRED:test-" + UUID.randomUUID();
        notificationService.onNotification(json.writeValueAsString(new NotificationRequested(
                NotificationType.HOLD_EXPIRED, key, "nobody-" + UUID.randomUUID(), EVENT_ID, List.of("B04"), "b-2", null, null, null)));

        assertThat(notificationLog.findById(key)).get()
                .extracting(NotificationRecord::status).isEqualTo(NotificationRecord.Status.SKIPPED);
    }

    @Test
    void reminder_isSentOncePerUserPerEvent() throws Exception {
        Event event = eventAdmin.create(new EventAdminService.CreateEventRequest(
                "Reminder Gig", "Small Hall", Instant.now().plus(Duration.ofHours(2)), 1, 5, new BigDecimal("100")));
        String email = user("pia");
        for (String seat : List.of("A01", "A02")) {
            String id = bookingService.hold("pia", UUID.randomUUID().toString(), event.id(), seat).booking().id();
            bookingService.pay("pia", id, PaymentOutcome.SUCCESS);
            await("confirmation of " + seat, () -> bookings.findById(id).map(b -> b.getStatus() == BookingStatus.CONFIRMED).orElse(false));
        }

        assertThat(reminders.remind(event.id())).isEqualTo(1);   // one user, two seats -> one reminder
        assertThat(reminders.remind(event.id())).isZero();       // never twice

        assertThat(raw(awaitMail(email, "Reminder: "))).contains("Row A Seat 1, Row A Seat 2");
    }

    // ---------- helpers ----------

    private String user(String name) {
        String email = name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        users.findByUsername(name).ifPresent(users::delete);
        users.insert(new User(null, name, "{noop}unused", List.of("USER"), email, true));
        return email;
    }

    private HoldOutcome hold(String user, String seat) {
        return bookingService.hold(user, UUID.randomUUID().toString(), EVENT_ID, seat);
    }

    private List<MimeMessage> sentTo(String email) {
        return Mockito.mockingDetails(mailSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send") && i.getArguments().length == 1
                        && i.getArguments()[0] instanceof MimeMessage)
                .map(i -> (MimeMessage) i.getArguments()[0])
                .filter(m -> {
                    try {
                        return Arrays.stream(m.getRecipients(Message.RecipientType.TO)).anyMatch(a -> a.toString().equals(email));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }

    private MimeMessage awaitMail(String email, String subjectPart) throws InterruptedException {
        await("email '" + subjectPart + "' to " + email, () -> sentTo(email).stream().anyMatch(m -> subject(m).contains(subjectPart)));
        return sentTo(email).stream().filter(m -> subject(m).contains(subjectPart)).findFirst().orElseThrow();
    }

    private static String subject(MimeMessage m) {
        try {
            return m.getSubject();
        } catch (Exception e) {
            return "";
        }
    }

    private static String raw(MimeMessage m) throws Exception {
        m.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
