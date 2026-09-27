package com.flashsale.seat;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One document per seat, so concurrent writes spread across many small documents
 * instead of all hitting one event document.
 *
 * {@code version} is a plain counter, not {@code @Version}: optimistic locking would stop the
 * naive baseline from overselling, and that baseline exists to show the race.
 */
@Document("seats")
public class Seat {

    @Id
    private String id;
    private long eventId;
    private String seatNo;
    private SeatStatus status;
    private long version;

    public String getId() { return id; }
    public long getEventId() { return eventId; }
    public String getSeatNo() { return seatNo; }
    public SeatStatus getStatus() { return status; }
    public long getVersion() { return version; }

    public void setStatus(SeatStatus status) { this.status = status; }
    public void setVersion(long version) { this.version = version; }
}
