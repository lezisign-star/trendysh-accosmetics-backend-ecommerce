package com.nbh.backend.imagecleanup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLimiterTest {

    @Test
    void neverMoreCallsAtOnceThanAllowed() throws Exception {
        GeminiLimiter limiter = new GeminiLimiter(2, 0);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch done = new CountDownLatch(6);
        for (int i = 0; i < 6; i++) {
            pool.execute(() -> {
                limiter.call(() -> {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                    return null;
                });
                done.countDown();
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(most.get()).isEqualTo(2);
    }

    @Test
    void callsStartNoFasterThanTheRateAllows() {
        GeminiLimiter limiter = new GeminiLimiter(5, 600); // one every 100 ms
        List<Long> starts = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            limiter.call(() -> starts.add(System.nanoTime()));
        }
        // The promise is "at most 600 a minute": the i-th call starts no earlier than i x 100 ms after the first turn.
        // (One gap may look shorter when the call before it happened to start a little late.)
        for (int i = 1; i < starts.size(); i++) {
            assertThat((starts.get(i) - t0) / 1_000_000).isGreaterThanOrEqualTo(i * 100L);
        }
    }

    @Test
    void theSlotIsGivenBackWhenACallFails() {
        GeminiLimiter limiter = new GeminiLimiter(1, 0);
        for (int i = 0; i < 3; i++) {
            try {
                limiter.call(() -> {
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                // the next call must still get the one slot
            }
        }
        assertThat(limiter.call(() -> "still works")).isEqualTo("still works");
    }
}
