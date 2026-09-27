package com.flashsale.seat;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends MongoRepository<Seat, String> {

    Optional<Seat> findByEventIdAndSeatNo(long eventId, String seatNo);

    List<Seat> findByEventIdOrderBySeatNo(long eventId);

    long countByEventIdAndStatus(long eventId, SeatStatus status);
}
