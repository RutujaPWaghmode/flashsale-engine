package com.flashsale.seat;

import com.flashsale.common.NotFoundException;
import org.springframework.stereotype.Service;

/**
 * Baseline (a): read, check, then write. Deliberately racy: two threads can both read AVAILABLE
 * and both "win". The concurrency test is expected to show overselling here.
 */
@Service
public class NaiveBookingService {

    private final SeatRepository seats;

    public NaiveBookingService(SeatRepository seats) {
        this.seats = seats;
    }

    public boolean book(long eventId, String seatNo) {
        Seat seat = seats.findByEventIdAndSeatNo(eventId, seatNo)
                .orElseThrow(() -> new NotFoundException("Seat " + seatNo + " not found"));
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            return false;
        }
        seat.setStatus(SeatStatus.BOOKED);
        seat.setVersion(seat.getVersion() + 1);
        seats.save(seat);          // race window between the read above and this write
        return true;
    }
}
