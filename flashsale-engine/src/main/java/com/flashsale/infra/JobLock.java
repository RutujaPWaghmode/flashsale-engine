package com.flashsale.infra;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Cluster-wide lock for scheduled jobs, stored in MongoDB (the ShedLock technique). With several
 * app instances, each tick of a job runs on at most one of them.
 *
 * Acquire = upsert "where _id = name and lockedUntil <= now". If another instance holds the lock the
 * filter matches nothing, the upsert tries to insert the same _id and fails with a duplicate key:
 * not acquired. {@code lockAtMost} bounds how long a crashed holder can block the job.
 */
@Component
public class JobLock {

    private static final String COLLECTION = "job_locks";

    private final MongoTemplate mongo;
    private final String instanceId = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID();

    public JobLock(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Runs {@code job} if this instance gets the lock; returns false if another instance holds it. */
    public boolean runExclusively(String name, Duration lockAtMost, Runnable job) {
        if (!tryAcquire(name, lockAtMost)) {
            return false;
        }
        try {
            job.run();
            return true;
        } finally {
            release(name);
        }
    }

    boolean tryAcquire(String name, Duration lockAtMost) {
        Instant now = Instant.now();
        try {
            mongo.upsert(query(where("_id").is(name).and("lockedUntil").lte(now)),
                    new Update().set("lockedUntil", now.plus(lockAtMost)).set("lockedAt", now).set("lockedBy", instanceId),
                    COLLECTION);
            return true;
        } catch (DuplicateKeyException heldElsewhere) {
            return false;
        }
    }

    void release(String name) {
        mongo.updateFirst(query(where("_id").is(name).and("lockedBy").is(instanceId)),
                new Update().set("lockedUntil", Instant.now()), COLLECTION);
    }
}
