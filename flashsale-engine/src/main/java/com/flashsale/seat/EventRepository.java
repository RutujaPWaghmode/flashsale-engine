package com.flashsale.seat;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends MongoRepository<Event, Long> {

    List<Event> findAllByOrderByStartTime();

    List<Event> findByStartTimeBetweenOrderByStartTime(Instant from, Instant to);
}
