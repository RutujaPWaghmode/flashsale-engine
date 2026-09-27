package com.flashsale.booking;

import java.math.BigDecimal;
import java.time.Instant;

public record BookingView(String id, long eventId, String seatNo, BookingStatus status, BigDecimal amount,
                          Instant holdExpiresAt, Instant createdAt, Instant updatedAt, String failureReason) {

    public static BookingView of(Booking b) {
        return new BookingView(b.getId(), b.getEventId(), b.getSeatNo(), b.getStatus(), b.getAmount(),
                b.getHoldExpiresAt(), b.getCreatedAt(), b.getUpdatedAt(), b.getFailureReason());
    }
}
