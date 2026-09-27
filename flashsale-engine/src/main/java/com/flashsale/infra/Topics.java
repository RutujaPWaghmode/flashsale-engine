package com.flashsale.infra;

/** Kafka topics. Five topics, so the design still fits Aiven's free tier (max 5). */
public final class Topics {

    private Topics() {}

    public static final String PAYMENT_REQUESTED = "payment-requested";
    public static final String PAYMENT_SUCCEEDED = "payment-succeeded";
    public static final String PAYMENT_FAILED = "payment-failed";
    public static final String NOTIFICATIONS = "notifications";
    public static final String BOOKING_DLQ = "booking-dlq";
}
