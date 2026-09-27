package com.flashsale.booking;

/**
 * Booking state machine.
 *
 * <pre>
 * HELD ──pay──> PAYMENT_PENDING ──ok──> CONFIRMED
 *  │                  │ ├──declined──> FAILED
 *  │                  │ └──hold lost / seat sold──> REFUNDED
 *  │                  └──hold expired──> EXPIRED ──late payment──> REFUNDED
 *  ├──user releases──> CANCELLED
 *  └──TTL──> EXPIRED
 * </pre>
 */
public enum BookingStatus {
    HELD,
    PAYMENT_PENDING,
    CONFIRMED,
    FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED;

    public boolean canTransitionTo(BookingStatus next) {
        return switch (this) {
            case HELD -> next == PAYMENT_PENDING || next == EXPIRED || next == CANCELLED;
            case PAYMENT_PENDING -> next == CONFIRMED || next == FAILED || next == REFUNDED || next == EXPIRED;
            case EXPIRED -> next == REFUNDED;
            case CONFIRMED, FAILED, CANCELLED, REFUNDED -> false;
        };
    }

    public boolean isFinal() {
        return this == CONFIRMED || this == FAILED || this == CANCELLED || this == REFUNDED;
    }
}
