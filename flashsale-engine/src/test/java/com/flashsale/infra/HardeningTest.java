package com.flashsale.infra;

import com.flashsale.booking.Booking;
import com.flashsale.booking.BookingRepository;
import com.flashsale.booking.BookingService;
import com.flashsale.booking.HoldOutcome;
import com.flashsale.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Leader lock for scheduled jobs, TTL retention, and per-event idempotency scope. */
class HardeningTest extends IntegrationTest {

    @Autowired MongoTemplate mongo;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookings;

    // ---------- leader lock ----------

    @Test
    void onlyOneInstanceRunsAJobAtATime() {
        JobLock instanceA = new JobLock(mongo);
        JobLock instanceB = new JobLock(mongo);
        String job = "test-job-" + UUID.randomUUID();
        AtomicInteger runsOfB = new AtomicInteger();

        boolean ranA = instanceA.runExclusively(job, Duration.ofMinutes(1), () ->
                // While A holds the lock, B must be refused.
                assertThat(instanceB.runExclusively(job, Duration.ofMinutes(1), runsOfB::incrementAndGet)).isFalse());

        assertThat(ranA).isTrue();
        assertThat(runsOfB).hasValue(0);
        assertThat(instanceB.runExclusively(job, Duration.ofMinutes(1), runsOfB::incrementAndGet)).as("after release").isTrue();
        assertThat(runsOfB).hasValue(1);
    }

    @Test
    void aCrashedHolderBlocksTheJobOnlyUntilLockAtMost() throws Exception {
        JobLock crashed = new JobLock(mongo);
        JobLock survivor = new JobLock(mongo);
        String job = "test-job-" + UUID.randomUUID();

        assertThat(crashed.tryAcquire(job, Duration.ofMillis(300))).isTrue();   // never released
        assertThat(survivor.tryAcquire(job, Duration.ofMinutes(1))).isFalse();
        Thread.sleep(400);
        assertThat(survivor.tryAcquire(job, Duration.ofMinutes(1))).isTrue();
    }

    // ---------- retention ----------

    @Test
    void ttlIndexesExist() {
        assertThat(ttl("outbox", "outbox_ttl")).isEqualTo(Duration.ofDays(7));
        assertThat(ttl("notifications", "notifications_ttl")).isEqualTo(Duration.ofDays(90));
        assertThat(ttl("bookings", "bookings_purge_ttl")).isEqualTo(Duration.ZERO);
        assertThat(ttl("email_verifications", "email_verifications_ttl")).isEqualTo(Duration.ZERO);
    }

    @Test
    void abandonedBookings_getAPurgeDate_confirmedOnesNever() {
        String id = hold("ttl-user", "J09").booking().id();
        bookingService.cancel("ttl-user", id);

        Instant purgeAt = mongo.findById(id, org.bson.Document.class, "bookings").getDate("purgeAt").toInstant();
        assertThat(purgeAt).isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));

        String live = hold("ttl-user", "J10").booking().id();
        assertThat(mongo.findById(live, org.bson.Document.class, "bookings").containsKey("purgeAt")).isFalse();
    }

    // ---------- idempotency scope ----------

    @Test
    void resettingOneEvent_keepsIdempotencyKeysOfOtherEvents() {
        String key = UUID.randomUUID().toString();
        HoldOutcome first = bookingService.hold("scope-user", key, 2, "A01");
        assertThat(first.status()).isEqualTo(HoldOutcome.Status.HELD);

        resetService.reset(EVENT_ID);   // a different event

        HoldOutcome again = bookingService.hold("scope-user", key, 2, "A01");
        assertThat(again.status()).isEqualTo(HoldOutcome.Status.DUPLICATE);
        assertThat(again.booking().id()).isEqualTo(first.booking().id());

        resetService.reset(2);          // clean up event 2 for other tests
    }

    @Test
    void sameClientKeyOnTwoEvents_givesTwoBookings() {
        String key = UUID.randomUUID().toString();
        HoldOutcome a = bookingService.hold("scope-user2", key, EVENT_ID, "J08");
        HoldOutcome b = bookingService.hold("scope-user2", key, 2, "A02");
        assertThat(a.status()).isEqualTo(HoldOutcome.Status.HELD);
        assertThat(b.status()).isEqualTo(HoldOutcome.Status.HELD);
        assertThat(a.booking().id()).isNotEqualTo(b.booking().id());
        resetService.reset(2);
    }

    private HoldOutcome hold(String user, String seat) {
        return bookingService.hold(user, UUID.randomUUID().toString(), EVENT_ID, seat);
    }

    private Duration ttl(String collection, String indexName) {
        List<IndexInfo> indexes = mongo.indexOps(collection).getIndexInfo();
        return indexes.stream().filter(i -> i.getName().equals(indexName)).findFirst().orElseThrow()
                .getExpireAfter().orElseThrow();
    }
}
