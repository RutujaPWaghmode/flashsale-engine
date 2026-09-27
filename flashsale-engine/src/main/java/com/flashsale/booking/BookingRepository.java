package com.flashsale.booking;

import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends MongoRepository<Booking, String> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByUserIdAndEventIdOrderByCreatedAtDesc(String userId, long eventId, Limit limit);

    List<Booking> findByEventIdAndStatus(long eventId, BookingStatus status);

    List<Booking> findByEventId(long eventId);

    long countByEventIdAndStatus(long eventId, BookingStatus status);

    void deleteByEventId(long eventId);
}
