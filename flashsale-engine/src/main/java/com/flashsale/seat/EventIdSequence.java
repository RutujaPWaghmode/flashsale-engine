package com.flashsale.seat;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/** Numeric event ids (they appear in Redis keys and URLs). One atomic counter document. */
@Component
public class EventIdSequence {

    record Counter(@Id String id, long seq) {}

    private static final String COLLECTION = "counters";
    private static final String NAME = "events";

    private final MongoTemplate mongo;

    public EventIdSequence(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public long next() {
        Counter counter = mongo.findAndModify(query(where("_id").is(NAME)), new Update().inc("seq", 1),
                FindAndModifyOptions.options().returnNew(true).upsert(true), Counter.class, COLLECTION);
        return counter.seq();
    }

    /** Makes sure the next id is above {@code existingId} (used after seeding fixed ids). */
    public void ensureAtLeast(long existingId) {
        mongo.upsert(query(where("_id").is(NAME)), new Update().max("seq", existingId), COLLECTION);
    }
}
