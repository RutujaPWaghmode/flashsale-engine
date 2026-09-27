package com.flashsale.seat;

import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Baseline (b): the filter and the update run as one atomic operation on a single document,
 * so exactly one caller wins. This is the "before" number for the k6 comparison against Redis.
 */
@Service
public class AtomicDbBookingService {

    private final MongoTemplate mongo;

    public AtomicDbBookingService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public boolean book(long eventId, String seatNo) {
        UpdateResult result = mongo.updateFirst(
                query(where("eventId").is(eventId)
                        .and("seatNo").is(seatNo)
                        .and("status").is(SeatStatus.AVAILABLE)),
                new Update().set("status", SeatStatus.BOOKED).inc("version", 1),
                Seat.class);
        return result.getModifiedCount() == 1;
    }
}
