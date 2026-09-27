package com.flashsale.payment;

/** What the mock payment provider should do. Lets the UI and tests drive every saga path. */
public enum PaymentOutcome {
    SUCCESS,
    DECLINE,
    SLOW,     // succeeds after a long delay, useful for watching the hold extension and expiry paths
    RANDOM    // succeeds with probability app.payment.random-success-rate
}
