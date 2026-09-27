package com.flashsale.infra;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Polls the outbox and publishes to Kafka. If Kafka is down, sends fail and the rows stay
 * unpublished until the next tick. At-least-once: a crash between send and mark means a
 * duplicate send, which every consumer tolerates. Published rows are deleted by a TTL index.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final MongoTemplate mongo;
    private final KafkaTemplate<String, String> kafka;
    private final FlashsaleMetrics metrics;
    private final JobLock lock;

    public OutboxPublisher(MongoTemplate mongo, KafkaTemplate<String, String> kafka,
                           FlashsaleMetrics metrics, MeterRegistry registry, JobLock lock) {
        this.mongo = mongo;
        this.kafka = kafka;
        this.metrics = metrics;
        this.lock = lock;
        Gauge.builder("outbox.pending", this::pendingCount).register(registry);
    }

    /** Only one instance publishes at a time: no duplicate sends between instances, and order is kept. */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:200}")
    public void publishPending() {
        lock.runExclusively("outbox-publisher", Duration.ofSeconds(10), this::publishBatch);
    }

    private void publishBatch() {
        Query pending = query(where("published").is(false)).with(Sort.by("createdAt")).limit(100);
        List<OutboxMessage> batch = mongo.find(pending, OutboxMessage.class);
        for (OutboxMessage message : batch) {
            try {
                kafka.send(message.topic(), message.key(), message.payload()).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                metrics.outboxPublishFailed();
                log.warn("Outbox publish failed, will retry: {}", e.getMessage());
                return;   // keep order; try again next tick
            }
            mongo.updateFirst(query(where("id").is(message.id())),
                    new Update().set("published", true).set("publishedAt", Instant.now()),
                    OutboxMessage.class);
        }
    }

    private double pendingCount() {
        try {
            return mongo.count(query(where("published").is(false)), OutboxMessage.class);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
