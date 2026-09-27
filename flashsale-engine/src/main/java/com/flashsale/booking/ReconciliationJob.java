package com.flashsale.booking;

import com.flashsale.hold.SeatHoldService;
import com.flashsale.infra.FlashsaleMetrics;
import com.flashsale.infra.JobLock;
import com.flashsale.seat.Event;
import com.flashsale.seat.EventCatalog;
import com.flashsale.seat.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MongoDB is the source of truth for sales. If Redis lost data (crash without AOF, flush), a sold
 * seat would look free and could be held again. This job re-marks every confirmed sale in Redis,
 * and reports sales Redis has but MongoDB does not (usually a confirmation still in flight).
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final EventRepository events;
    private final EventCatalog catalog;
    private final BookingRepository bookings;
    private final SeatHoldService seatHolds;
    private final FlashsaleMetrics metrics;
    private final JobLock lock;

    public ReconciliationJob(EventRepository events, EventCatalog catalog, BookingRepository bookings,
                             SeatHoldService seatHolds, FlashsaleMetrics metrics, JobLock lock) {
        this.events = events;
        this.catalog = catalog;
        this.bookings = bookings;
        this.seatHolds = seatHolds;
        this.metrics = metrics;
        this.lock = lock;
    }

    @Scheduled(initialDelayString = "${app.reconciliation.initial-delay-ms:5000}",
               fixedDelayString = "${app.reconciliation.interval-ms:30000}")
    public void reconcileAll() {
        lock.runExclusively("reconciliation", Duration.ofMinutes(5), () -> {
            for (Event event : events.findAll()) {
                reconcile(event.id());
            }
        });
    }

    /** @return number of seats repaired in Redis */
    public int reconcile(long eventId) {
        Map<String, String> ownerBySeat = new HashMap<>();
        for (Booking b : bookings.findByEventIdAndStatus(eventId, BookingStatus.CONFIRMED)) {
            ownerBySeat.put(b.getSeatNo(), b.getUserId());
        }
        List<String> seatNos = catalog.find(eventId).map(EventCatalog.Entry::seatNos).orElse(List.of());
        List<String> raw = seatHolds.rawValues(eventId, seatNos);

        int repaired = 0;
        for (int i = 0; i < seatNos.size(); i++) {
            String seatNo = seatNos.get(i);
            String owner = ownerBySeat.get(seatNo);
            String soldTo = SeatHoldService.soldTo(raw.get(i));
            if (owner != null && !owner.equals(soldTo)) {
                seatHolds.markSold(eventId, seatNo, owner);
                metrics.drift("redis_missing_sale");
                log.warn("Reconciliation: restored sale of {}/{} to {} in Redis", eventId, seatNo, owner);
                repaired++;
            } else if (owner == null && soldTo != null) {
                metrics.drift("redis_only_sale");
                log.info("Reconciliation: Redis marks {}/{} sold to {} but MongoDB has no confirmed booking yet",
                        eventId, seatNo, soldTo);
            }
        }
        return repaired;
    }
}
