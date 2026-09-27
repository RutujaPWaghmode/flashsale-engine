package com.flashsale.payment;

import java.math.BigDecimal;

/**
 * Command on payment-requested, always written through the outbox by the booking service.
 * {@code action} = PAY (charge) or REFUND (compensation); older messages without it mean PAY.
 */
public record PaymentRequested(String bookingId, long eventId, String seatNo, String userId,
                               BigDecimal amount, PaymentOutcome outcome, Action action, String reason) {

    public enum Action { PAY, REFUND }

    public static PaymentRequested pay(String bookingId, long eventId, String seatNo, String userId,
                                       BigDecimal amount, PaymentOutcome outcome) {
        return new PaymentRequested(bookingId, eventId, seatNo, userId, amount, outcome, Action.PAY, null);
    }

    public static PaymentRequested refund(String bookingId, long eventId, String seatNo, String userId,
                                          BigDecimal amount, String reason) {
        return new PaymentRequested(bookingId, eventId, seatNo, userId, amount, null, Action.REFUND, reason);
    }

    public boolean isRefund() {
        return action == Action.REFUND;
    }
}
