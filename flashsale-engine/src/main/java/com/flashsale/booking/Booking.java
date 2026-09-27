package com.flashsale.booking;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;

@Document("bookings")
public class Booking {

    @Id
    private String id;
    private String userId;
    private long eventId;
    private String seatNo;
    private BookingStatus status;
    /** userId:eventId:client key. Unique index: a retried request cannot create a second booking. */
    private String idempotencyKey;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;
    private String paymentId;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant holdExpiresAt;

    public static Booking held(String userId, String idempotencyKey, long eventId, String seatNo,
                               BigDecimal amount, Instant holdExpiresAt) {
        Booking b = new Booking();
        b.userId = userId;
        b.idempotencyKey = idempotencyKey;
        b.eventId = eventId;
        b.seatNo = seatNo;
        b.amount = amount;
        b.status = BookingStatus.HELD;
        b.createdAt = Instant.now();
        b.updatedAt = b.createdAt;
        b.holdExpiresAt = holdExpiresAt;
        return b;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public long getEventId() { return eventId; }
    public String getSeatNo() { return seatNo; }
    public BookingStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public String getPaymentId() { return paymentId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
}
