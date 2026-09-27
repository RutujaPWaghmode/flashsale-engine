package com.flashsale.infra;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Writes an event to the outbox collection. Call it inside the same MongoDB transaction as the
 * state change, so the event exists if and only if the change committed (no dual-write problem).
 */
@Service
public class OutboxService {

    private final MongoTemplate mongo;
    private final JsonMapper json;

    public OutboxService(MongoTemplate mongo, JsonMapper json) {
        this.mongo = mongo;
        this.json = json;
    }

    public void enqueue(String topic, String key, String aggregateId, Object payload) {
        mongo.insert(new OutboxMessage(null, topic, key, aggregateId, json.writeValueAsString(payload),
                false, Instant.now(), null));
    }
}
