package com.flashsale.notification;

public enum NotificationType {
    BOOKING_CONFIRMED,   // with an .ics calendar invite attached
    PAYMENT_FAILED,
    BOOKING_REFUNDED,
    HOLD_EXPIRED,
    EVENT_REMINDER,      // sent app.notifications.reminder-before ahead of the event, one per user
    EMAIL_VERIFICATION;  // the only type sent to an unverified address

    public boolean needsEvent() {
        return this != EMAIL_VERIFICATION;
    }
}
