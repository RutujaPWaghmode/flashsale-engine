package com.flashsale.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.springframework.data.domain.Sort.Direction.ASC;

@Configuration
public class MongoConfig {

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory);   // enables transactions (needs a replica set)
    }

    @Bean
    TransactionTemplate transactionTemplate(MongoTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** MongoDB has no schema migrations: indexes are created at startup. Creating an existing index is a no-op. */
    @Bean
    @Order(0)
    ApplicationRunner createIndexes(MongoTemplate mongo,
                                    @Value("${app.retention.outbox:7d}") Duration outboxRetention,
                                    @Value("${app.retention.notifications:90d}") Duration notificationRetention) {
        return args -> {
            mongo.indexOps("users").createIndex(new Index().on("username", ASC).unique());

            mongo.indexOps("seats").createIndex(
                    new Index().on("eventId", ASC).on("seatNo", ASC).unique());

            mongo.indexOps("bookings").createIndex(
                    new Index().on("idempotencyKey", ASC).unique());
            mongo.indexOps("bookings").createIndex(
                    new Index().on("eventId", ASC).on("seatNo", ASC).unique()
                            .partial(PartialIndexFilter.of(Criteria.where("status").is("CONFIRMED"))));

            mongo.indexOps("payments").createIndex(new Index().on("bookingId", ASC).unique());
            mongo.indexOps("bookings").createIndex(
                    new Index().on("userId", ASC).on("eventId", ASC).on("createdAt", ASC));
            mongo.indexOps("bookings").createIndex(
                    new Index().on("status", ASC).on("holdExpiresAt", ASC));

            mongo.indexOps("notifications").createIndex(new Index().on("eventId", ASC));

            mongo.indexOps("outbox").createIndex(new Index().on("published", ASC).on("createdAt", ASC));

            // Retention (TTL indexes): MongoDB deletes these documents in the background, about once a minute.
            // Only documents that have the field are affected: unpublished outbox rows and live bookings stay.
            mongo.indexOps("outbox").createIndex(
                    new Index().on("publishedAt", ASC).expire(outboxRetention).named("outbox_ttl"));
            mongo.indexOps("notifications").createIndex(
                    new Index().on("createdAt", ASC).expire(notificationRetention).named("notifications_ttl"));
            mongo.indexOps("bookings").createIndex(
                    new Index().on("purgeAt", ASC).expire(Duration.ZERO).named("bookings_purge_ttl"));
            mongo.indexOps("email_verifications").createIndex(
                    new Index().on("expiresAt", ASC).expire(Duration.ZERO).named("email_verifications_ttl"));
        };
    }
}
