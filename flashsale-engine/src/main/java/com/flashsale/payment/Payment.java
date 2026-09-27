package com.flashsale.payment;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;

/** One payment per booking (unique index on bookingId), so a redelivered request never charges twice. */
@Document("payments")
public record Payment(@Id String id,
                      String bookingId,
                      @Field(targetType = FieldType.DECIMAL128) BigDecimal amount,
                      PaymentStatus status,
                      String reason,
                      Instant createdAt) {

    public enum PaymentStatus { SUCCEEDED, FAILED, REFUNDED }
}
