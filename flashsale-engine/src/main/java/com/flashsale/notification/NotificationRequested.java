package com.flashsale.notification;

import java.math.BigDecimal;
import java.util.List;

/**
 * Message on the notifications topic. {@code dedupeKey} identifies the notification itself
 * (e.g. BOOKING_CONFIRMED:bookingId), so a redelivered message never sends a second email.
 */
public record NotificationRequested(NotificationType type,
                                    String dedupeKey,
                                    String userId,
                                    long eventId,
                                    List<String> seatNos,
                                    String bookingId,
                                    BigDecimal amount,
                                    String reason,
                                    String actionUrl) {}
