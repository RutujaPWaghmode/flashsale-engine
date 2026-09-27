package com.flashsale.notification;

import com.flashsale.infra.Topics;
import com.flashsale.seat.Event;
import com.flashsale.seat.EventRepository;
import com.flashsale.security.User;
import com.flashsale.security.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Consumes the notifications topic and sends email. A separate consumer group, so a slow or
 * broken mail server only delays email: bookings keep flowing. Failures are retried, then
 * dead-lettered like every other listener.
 *
 * Delivery is at-least-once per email: a crash between "sent" and "recorded" re-sends one mail.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRecordRepository records;
    private final UserRepository users;
    private final EventRepository events;
    private final EmailComposer composer;
    private final JavaMailSender mailSender;
    private final JsonMapper json;
    private final MeterRegistry registry;
    private final String from;

    public NotificationService(NotificationRecordRepository records, UserRepository users, EventRepository events,
                               EmailComposer composer, JavaMailSender mailSender, JsonMapper json, MeterRegistry registry,
                               @Value("${app.mail.from:FlashSale <tickets@flashsale.local>}") String from) {
        this.records = records;
        this.users = users;
        this.events = events;
        this.composer = composer;
        this.mailSender = mailSender;
        this.json = json;
        this.registry = registry;
        this.from = from;
    }

    @KafkaListener(topics = Topics.NOTIFICATIONS, groupId = "notification-service")
    public void onNotification(String message) throws Exception {
        NotificationRequested n = json.readValue(message, NotificationRequested.class);
        if (records.existsById(n.dedupeKey())) {
            count(n.type(), "duplicate");
            return;
        }
        User user = users.findByUsername(n.userId()).orElse(null);
        if (user == null || user.email() == null || user.email().isBlank()) {
            skip(n, "user has no email address");
            return;
        }
        if (n.type() != NotificationType.EMAIL_VERIFICATION && !user.isEmailVerified()) {
            skip(n, "email address not verified");
            return;
        }
        Event event = null;
        if (n.type().needsEvent()) {
            event = events.findById(n.eventId()).orElse(null);
            if (event == null) {
                skip(n, "event no longer exists");
                return;
            }
        }

        EmailComposer.Email email = n.type() == NotificationType.EMAIL_VERIFICATION
                ? composer.verification(user.username(), n.actionUrl())
                : composer.compose(n, user.username(), event);
        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(user.email());
        helper.setSubject(email.subject());
        helper.setText(email.text(), email.html());
        if (email.calendarInvite() != null) {
            helper.addAttachment("ticket.ics", new ByteArrayResource(email.calendarInvite().getBytes(StandardCharsets.UTF_8)),
                    "text/calendar; charset=UTF-8; method=PUBLISH");
        }
        mailSender.send(mime);

        records.save(new NotificationRecord(n.dedupeKey(), n.type(), n.userId(), n.eventId(), user.email(),
                email.subject(), NotificationRecord.Status.SENT, null, Instant.now()));
        count(n.type(), "sent");
        log.info("Sent {} email to {} ({})", n.type(), user.username(), n.dedupeKey());
    }

    private void skip(NotificationRequested n, String why) {
        records.save(new NotificationRecord(n.dedupeKey(), n.type(), n.userId(), n.eventId(), null, null,
                NotificationRecord.Status.SKIPPED, why, Instant.now()));
        count(n.type(), "skipped");
    }

    private void count(NotificationType type, String outcome) {
        registry.counter("notifications", "type", type.name(), "outcome", outcome).increment();
    }
}
