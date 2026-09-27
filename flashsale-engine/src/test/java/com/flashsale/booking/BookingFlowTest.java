package com.flashsale.booking;

import com.flashsale.hold.SeatKeys;
import com.flashsale.payment.Payment;
import com.flashsale.payment.PaymentOutcome;
import com.flashsale.payment.PaymentRepository;
import com.flashsale.seat.SeatQueryService;
import com.flashsale.seat.SeatStatus;
import com.flashsale.support.IntegrationTest;
import com.flashsale.support.Race;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end through Redis, MongoDB and Kafka: every branch of the saga. */
class BookingFlowTest extends IntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired SeatQueryService seatQuery;
    @Autowired ReconciliationJob reconciliation;
    @Autowired StringRedisTemplate redis;

    @Test
    void sameIdempotencyKey50Times_createsOneBooking() throws Exception {
        String key = UUID.randomUUID().toString();
        int newHolds = Race.run(50, i ->
                bookingService.hold("alice", key, EVENT_ID, "A01").status() == HoldOutcome.Status.HELD);

        assertThat(newHolds).isEqualTo(1);
        assertThat(bookings.findByEventId(EVENT_ID)).hasSize(1);
        assertThat(bookingService.hold("alice", key, EVENT_ID, "A01").status()).isEqualTo(HoldOutcome.Status.DUPLICATE);
    }

    @Test
    void perUserSeatCap_isEnforced() {
        for (String seat : new String[]{"A01", "A02", "A03", "A04"}) {
            assertThat(hold("bob", seat).status()).isEqualTo(HoldOutcome.Status.HELD);
        }
        assertThat(hold("bob", "A05").status()).isEqualTo(HoldOutcome.Status.HOLD_LIMIT);
        assertThat(hold("carol", "A05").status()).isEqualTo(HoldOutcome.Status.HELD);
    }

    @Test
    void secondUser_cannotHoldAHeldSeat() {
        assertThat(hold("alice", "B01").status()).isEqualTo(HoldOutcome.Status.HELD);
        assertThat(hold("bob", "B01").status()).isEqualTo(HoldOutcome.Status.SEAT_TAKEN);
        assertThat(seatQuery.current(EVENT_ID, "B01")).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void successfulPayment_confirmsBookingAndSellsSeat() throws Exception {
        String id = hold("alice", "C01").booking().id();

        bookingService.pay("alice", id, PaymentOutcome.SUCCESS);

        awaitStatus(id, BookingStatus.CONFIRMED);
        assertThat(seatQuery.current(EVENT_ID, "C01")).isEqualTo(SeatStatus.BOOKED);
        assertThat(redis.opsForValue().get(SeatKeys.seat(EVENT_ID, "C01"))).isEqualTo("SOLD:alice");
        assertThat(payments.findByBookingId(id)).get().extracting(Payment::status).isEqualTo(Payment.PaymentStatus.SUCCEEDED);
    }

    @Test
    void declinedPayment_releasesSeat() throws Exception {
        String id = hold("alice", "D01").booking().id();

        bookingService.pay("alice", id, PaymentOutcome.DECLINE);

        awaitStatus(id, BookingStatus.FAILED);
        assertThat(seatQuery.current(EVENT_ID, "D01")).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(hold("bob", "D01").status()).isEqualTo(HoldOutcome.Status.HELD);
    }

    @Test
    void cancel_releasesSeat() {
        String id = hold("alice", "E01").booking().id();

        assertThat(bookingService.cancel("alice", id).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seatQuery.current(EVENT_ID, "E01")).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void redisDataLoss_reconciliationRestoresSale() throws Exception {
        String id = hold("alice", "F01").booking().id();
        bookingService.pay("alice", id, PaymentOutcome.SUCCESS);
        awaitStatus(id, BookingStatus.CONFIRMED);

        redis.delete(SeatKeys.seat(EVENT_ID, "F01"));          // simulate Redis losing the key

        assertThat(reconciliation.reconcile(EVENT_ID)).isEqualTo(1);
        assertThat(hold("bob", "F01").status()).isEqualTo(HoldOutcome.Status.SEAT_TAKEN);
    }

    @Test
    void redisDataLoss_uniqueIndexStillBlocksOversell() throws Exception {
        String first = hold("alice", "G01").booking().id();
        bookingService.pay("alice", first, PaymentOutcome.SUCCESS);
        awaitStatus(first, BookingStatus.CONFIRMED);

        redis.delete(SeatKeys.seat(EVENT_ID, "G01"));          // Redis forgets, reconciliation has not run yet
        String second = hold("bob", "G01").booking().id();      // so a second hold succeeds...
        bookingService.pay("bob", second, PaymentOutcome.SUCCESS);

        awaitStatus(second, BookingStatus.REFUNDED);             // ...but MongoDB refuses a second sale
        assertThat(bookings.countByEventIdAndStatus(EVENT_ID, BookingStatus.CONFIRMED)).isEqualTo(1);
        await("refund command processed by the payment service",   // asynchronous, over Kafka
                () -> payments.findByBookingId(second).map(p -> p.status() == Payment.PaymentStatus.REFUNDED).orElse(false));
    }

    private HoldOutcome hold(String user, String seat) {
        return bookingService.hold(user, UUID.randomUUID().toString(), EVENT_ID, seat);
    }

    private void awaitStatus(String bookingId, BookingStatus status) throws InterruptedException {
        await("booking " + bookingId + " to become " + status,
                () -> bookings.findById(bookingId).map(b -> b.getStatus() == status).orElse(false));
    }
}
