package com.nbh.backend.imagecleanup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * One gate in front of every Gemini call (photos and text, the photo studio and the variant jobs alike), so several jobs
 * running side by side share the account's quota fairly instead of tripping it (VARIANTS_PLAN.md 3.3). At most
 * {@code app.gemini.max-concurrent} calls at once, started at most {@code app.gemini.requests-per-minute} a minute.
 * Set the rate from the Gemini plan the shop pays for; 0 = no rate limit.
 */
@Component
public class GeminiLimiter {

    private final Semaphore slots;
    private final long gapNanos;
    private long nextStart = System.nanoTime();

    public GeminiLimiter(@Value("${app.gemini.max-concurrent:2}") int maxConcurrent,
                         @Value("${app.gemini.requests-per-minute:30}") int requestsPerMinute) {
        this.slots = new Semaphore(Math.max(1, maxConcurrent), true);
        this.gapNanos = requestsPerMinute <= 0 ? 0 : 60_000_000_000L / requestsPerMinute;
    }

    public <T> T call(Supplier<T> work) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiImageClient.GeminiException("Stopped while waiting for a turn with Gemini.", false);
        }
        try {
            waitForTurn();
            return work.get();
        } finally {
            slots.release();
        }
    }

    private void waitForTurn() {
        long start;
        synchronized (this) {
            start = Math.max(System.nanoTime(), nextStart);
            nextStart = start + gapNanos;
        }
        // Sleep can wake a little early (Windows' timer), so wait until the turn has really come.
        long wait;
        while ((wait = start - System.nanoTime()) > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
