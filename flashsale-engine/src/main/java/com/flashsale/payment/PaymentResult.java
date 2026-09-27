package com.flashsale.payment;

/** Message on payment-succeeded / payment-failed. */
public record PaymentResult(String bookingId, long eventId, String seatNo, String userId,
                            String paymentId, boolean succeeded, String reason) {}
