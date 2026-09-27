package com.flashsale.seat;

import com.flashsale.booking.BookingProperties;
import com.flashsale.common.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    public record EventView(long id, String name, String venue, Instant startTime, int totalSeats,
                            BigDecimal price, SeatQueryService.Counts seats, int maxSeatsPerUser, long holdMinutes,
                            boolean onSale) {}

    private final EventRepository events;
    private final SeatQueryService seatQuery;
    private final BookingProperties bookingProps;

    public EventController(EventRepository events, SeatQueryService seatQuery, BookingProperties bookingProps) {
        this.events = events;
        this.seatQuery = seatQuery;
        this.bookingProps = bookingProps;
    }

    /** Calendar feed, sorted by start time. Optional ISO-8601 range, e.g. ?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z */
    @GetMapping
    public List<EventView> list(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        List<Event> found = from == null && to == null
                ? events.findAllByOrderByStartTime()
                : events.findByStartTimeBetweenOrderByStartTime(from != null ? from : Instant.EPOCH,
                        to != null ? to : Instant.parse("9999-12-31T00:00:00Z"));
        return found.stream().map(this::view).toList();
    }

    @GetMapping("/{eventId}")
    public EventView get(@PathVariable long eventId) {
        return events.findById(eventId).map(this::view)
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
    }

    @GetMapping("/{eventId}/seats")
    public List<SeatQueryService.SeatView> seats(@PathVariable long eventId) {
        return seatQuery.seats(eventId);
    }

    public EventView view(Event e) {
        return new EventView(e.id(), e.name(), e.venue(), e.startTime(), e.totalSeats(), e.price(),
                seatQuery.counts(e.id()), bookingProps.maxSeatsPerUser(), bookingProps.holdTtl().toMinutes(),
                e.startTime().isAfter(Instant.now()));
    }
}
