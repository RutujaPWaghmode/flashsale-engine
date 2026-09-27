package com.flashsale.notification;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One row per notification, keyed by its dedupe key: the idempotency guard and a send log. */
@Document("notifications")
public record NotificationRecord(@Id String dedupeKey,
                                 NotificationType type,
                                 String userId,
                                 long eventId,
                                 String email,
                                 String subject,
                                 Status status,
                                 String detail,
                                 Instant createdAt) {

    public enum Status { SENT, SKIPPED }
}
