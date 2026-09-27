package com.flashsale.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

/** Fires N attempts at once (100 threads released by one latch) and counts the winners. */
public final class Race {

    private Race() {}

    public static int run(int attempts, IntPredicate attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                if (attempt.test(n)) {
                    wins.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        return wins.get();
    }
}
