package com.nbh.backend.tumaboda;

import com.nbh.backend.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the public address search and price quote from being used to run up TumaBoda calls on our API key
 * (or to get the key throttled for everyone): per caller, and across everyone as a backstop. Fixed
 * one-minute windows, in memory and per instance, like {@code LoginThrottle}. The address search is called
 * as a customer types, so its limits are the roomier ones; a quote is one call per address chosen.
 */
@Component
public class TumaBodaThrottle {

    static final int LOOKUPS_PER_CALLER = 60;
    static final int LOOKUPS_IN_TOTAL = 600;
    static final int QUOTES_PER_CALLER = 15;
    static final int QUOTES_IN_TOTAL = 200;
    private static final int TRIM_ABOVE = 10_000;
    private static final String MESSAGE = "You're searching too quickly. Please wait a moment and try again.";

    private record Window(long minute, int count) {}

    private final class Limiter {
        private final int limit;
        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

        Limiter(int limit) {
            this.limit = limit;
        }

        void hit(String key) {
            long minute = clock.millis() / 60_000;
            if (windows.size() > TRIM_ABOVE) {
                windows.values().removeIf(w -> w.minute() < minute);
            }
            windows.compute(key, (k, old) -> {
                if (old == null || old.minute() != minute) {
                    return new Window(minute, 1);
                }
                if (old.count() >= limit) {
                    throw new TooManyRequestsException(MESSAGE);
                }
                return new Window(minute, old.count() + 1);
            });
        }
    }

    private final Clock clock;
    private final Limiter lookupsPerCaller = new Limiter(LOOKUPS_PER_CALLER);
    private final Limiter lookupsInTotal = new Limiter(LOOKUPS_IN_TOTAL);
    private final Limiter quotesPerCaller = new Limiter(QUOTES_PER_CALLER);
    private final Limiter quotesInTotal = new Limiter(QUOTES_IN_TOTAL);

    public TumaBodaThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Counts an address search or place lookup by {@code caller}, or throws if they (or everyone) have done too many this minute. */
    public void lookup(String caller) {
        lookupsPerCaller.hit(caller);
        lookupsInTotal.hit("all");
    }

    /** Counts a price quote by {@code caller}, or throws. */
    public void quote(String caller) {
        quotesPerCaller.hit(caller);
        quotesInTotal.hit("all");
    }
}
