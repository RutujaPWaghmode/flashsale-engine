package com.flashsale.booking;

/** Result of POST /api/bookings. {@code booking} is set for HELD and DUPLICATE. */
public record HoldOutcome(Status status, String seatNo, BookingView booking) {

    public enum Status {
        HELD,          // 200: new hold
        DUPLICATE,     // 200: same Idempotency-Key seen before, original result returned
        SEAT_TAKEN,    // 409
        HOLD_LIMIT,    // 409: user already holds the per-event maximum
        IN_PROGRESS    // 409: the first request with this key is still running
    }

    public boolean succeeded() {
        return status == Status.HELD || status == Status.DUPLICATE;
    }
}
