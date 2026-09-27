package com.flashsale.seat;

import com.flashsale.common.NotFoundException;
import com.flashsale.hold.SeatHoldService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Seat status as users see it. MongoDB knows what is sold; Redis knows what is held right now
 * (holds expire on their own), so the view overlays one MGET on top of the seat documents.
 */
@Service
public class SeatQueryService {

    public record SeatView(String seatNo, SeatStatus status) {}

    public record Counts(int available, int held, int booked) {}

    private final EventCatalog catalog;
    private final SeatRepository seats;
    private final SeatHoldService seatHolds;

    public SeatQueryService(EventCatalog catalog, SeatRepository seats, SeatHoldService seatHolds) {
        this.catalog = catalog;
        this.seats = seats;
        this.seatHolds = seatHolds;
    }

    public List<SeatView> seats(long eventId) {
        EventCatalog.Entry entry = catalog.find(eventId)
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
        List<Seat> docs = seats.findByEventIdOrderBySeatNo(eventId);
        List<String> seatNos = docs.stream().map(Seat::getSeatNo).toList();
        List<String> raw = seatHolds.rawValues(entry.event().id(), seatNos);

        List<SeatView> views = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            SeatStatus status = docs.get(i).getStatus() == SeatStatus.BOOKED
                    ? SeatStatus.BOOKED
                    : SeatHoldService.statusOf(raw.get(i));
            views.add(new SeatView(seatNos.get(i), status));
        }
        return views;
    }

    public SeatStatus current(long eventId, String seatNo) {
        String raw = seatHolds.rawValues(eventId, List.of(seatNo)).getFirst();
        SeatStatus fromRedis = SeatHoldService.statusOf(raw);
        if (fromRedis != SeatStatus.AVAILABLE) {
            return fromRedis;
        }
        return seats.findByEventIdAndSeatNo(eventId, seatNo)
                .map(Seat::getStatus)
                .filter(s -> s == SeatStatus.BOOKED)
                .orElse(SeatStatus.AVAILABLE);
    }

    public Counts counts(long eventId) {
        int available = 0, held = 0, booked = 0;
        for (SeatView seat : seats(eventId)) {
            switch (seat.status()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case BOOKED -> booked++;
            }
        }
        return new Counts(available, held, booked);
    }
}
