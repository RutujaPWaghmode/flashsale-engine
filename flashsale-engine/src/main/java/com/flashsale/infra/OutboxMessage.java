package com.flashsale.infra;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("outbox")
public record OutboxMessage(@Id String id,
                            String topic,
                            String key,
                            String aggregateId,
                            String payload,
                            boolean published,
                            Instant createdAt,
                            Instant publishedAt) {}
