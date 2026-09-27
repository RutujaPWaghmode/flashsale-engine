package com.flashsale;

import com.flashsale.hold.SeatHoldService;
import com.flashsale.seat.AtomicDbBookingService;
import com.flashsale.seat.EventCatalog;
import com.flashsale.seat.NaiveBookingService;
import com.flashsale.seat.SeatRepository;
import com.flashsale.seat.SeatStatus;
import com.flashsale.support.IntegrationTest;
import com.flashsale.support.Race;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 1,000 simultaneous attempts at 100 seats (10 per seat): exactly 100 may win. */
class ConcurrencyTest extends IntegrationTest {

    private static final int ATTEMPTS = 1000;

    @Autowired EventCatalog catalog;
    @Autowired SeatRepository seats;
    @Autowired SeatHoldService seatHolds;
    @Autowired NaiveBookingService naive;
    @Autowired AtomicDbBookingService atomic;

    @Test
    void redisHold_neverOversells() throws Exception {
        List<String> seatNos = seatNos();
        int wins = Race.run(ATTEMPTS, i ->
                seatHolds.tryHold(EVENT_ID, seatNos.get(i % seatNos.size()), "user-" + i) == SeatHoldService.HoldResult.HELD);

        assertThat(wins).isEqualTo(seatNos.size());
    }

    @Test
    void atomicDbUpdate_neverOversells() throws Exception {
        List<String> seatNos = seatNos();
        int wins = Race.run(ATTEMPTS, i -> atomic.book(EVENT_ID, seatNos.get(i % seatNos.size())));

        assertThat(wins).isEqualTo(seatNos.size());
        assertThat(seats.countByEventIdAndStatus(EVENT_ID, SeatStatus.BOOKED)).isEqualTo(seatNos.size());
    }

    @Test
    void naiveReadThenWrite_oversells() throws Exception {
        List<String> seatNos = seatNos();
        int wins = Race.run(ATTEMPTS, i -> naive.book(EVENT_ID, seatNos.get(i % seatNos.size())));

        // Every win above 100 is a seat sold twice. Usually far above 100, but it is a race.
        System.out.printf("Naive baseline: %d successful bookings for %d seats%n", wins, seatNos.size());
        assertThat(wins).isGreaterThanOrEqualTo(seatNos.size());
    }

    private List<String> seatNos() {
        return catalog.find(EVENT_ID).orElseThrow().seatNos();
    }
}
