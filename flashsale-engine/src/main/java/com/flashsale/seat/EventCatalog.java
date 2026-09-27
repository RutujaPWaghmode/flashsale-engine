package com.flashsale.seat;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of events and their seat numbers. Seats never change during a sale, so the hot
 * path can validate a seat number without a MongoDB round trip.
 */
@Component
public class EventCatalog {

    public record Entry(Event event, List<String> seatNos, Set<String> seatSet) {}

    private final EventRepository events;
    private final SeatRepository seats;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public EventCatalog(EventRepository events, SeatRepository seats) {
        this.events = events;
        this.seats = seats;
    }

    public Optional<Entry> find(long eventId) {
        Entry cached = cache.get(eventId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return events.findById(eventId).map(event -> {
            List<String> seatNos = seats.findByEventIdOrderBySeatNo(eventId).stream().map(Seat::getSeatNo).toList();
            Entry entry = new Entry(event, seatNos, Set.copyOf(seatNos));
            cache.put(eventId, entry);
            return entry;
        });
    }

    public boolean hasSeat(long eventId, String seatNo) {
        return find(eventId).map(e -> e.seatSet().contains(seatNo)).orElse(false);
    }

    public void invalidate(long eventId) {
        cache.remove(eventId);
    }
}
