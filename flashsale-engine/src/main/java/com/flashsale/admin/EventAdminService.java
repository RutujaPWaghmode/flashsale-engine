package com.flashsale.admin;

import com.flashsale.seat.DataSeeder;
import com.flashsale.seat.Event;
import com.flashsale.seat.EventIdSequence;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
public class EventAdminService {

    public record CreateEventRequest(@NotBlank @Size(max = 100) String name,
                                     @NotBlank @Size(max = 100) String venue,
                                     @NotNull @Future Instant startTime,
                                     @Min(1) @Max(26) int rows,
                                     @Min(1) @Max(20) int seatsPerRow,
                                     @NotNull @DecimalMin("1.00") BigDecimal price) {}

    private final EventIdSequence ids;
    private final DataSeeder seeder;
    private final TransactionTemplate tx;

    public EventAdminService(EventIdSequence ids, DataSeeder seeder, TransactionTemplate tx) {
        this.ids = ids;
        this.seeder = seeder;
        this.tx = tx;
    }

    /** Event and all its seats in one transaction: an event never exists without seats. */
    public Event create(CreateEventRequest req) {
        long id = ids.next();
        Event event = new Event(id, req.name().trim(), req.venue().trim(), req.startTime(),
                req.rows() * req.seatsPerRow(), req.price().setScale(2, RoundingMode.HALF_UP));
        tx.executeWithoutResult(status -> seeder.create(event, req.rows(), req.seatsPerRow()));
        return event;
    }
}
