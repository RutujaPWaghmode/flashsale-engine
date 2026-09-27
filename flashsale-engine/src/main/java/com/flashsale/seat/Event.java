package com.flashsale.seat;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;

/** {@code _id} is a Long we assign so Redis keys stay short (seat:{evt1}:A01). */
@Document("events")
public record Event(@Id long id,
                    String name,
                    String venue,
                    Instant startTime,
                    int totalSeats,
                    @Field(targetType = FieldType.DECIMAL128) BigDecimal price) {}
