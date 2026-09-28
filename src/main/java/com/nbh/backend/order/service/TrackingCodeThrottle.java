package com.nbh.backend.order.service;

import com.nbh.backend.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops the order-lookup code being used to spam someone's inbox: at most {@value #MAX_PER_WINDOW}
 * codes per email per hour, and not more than one a minute. It counts EVERY request, including for
 * emails with no orders, so a refusal never reveals whether an email has any. In-memory and
 * per-instance, like {@code LoginThrottle}.
 */
@Component
public class TrackingCodeThrottle {

    static final int MAX_PER_WINDOW = 5;
    static final Duration WINDOW = Duration.ofHours(1);
    static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int TRIM_ABOVE = 10_000;

    private record Requests(int count, Instant windowStart, Instant last) {}

    private final ConcurrentHashMap<String, Requests> byEmail = new ConcurrentHashMap<>();
    private final Clock clock;

    public TrackingCodeThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Counts this request, or throws if the email has asked too often. {@code email} must be normalised. */
    public void checkAndRecord(String email) {
        Instant now = Instant.now(clock);
        if (byEmail.size() > TRIM_ABOVE) {
            byEmail.values().removeIf(r -> now.isAfter(r.windowStart().plus(WINDOW)));
        }
        byEmail.compute(email, (key, old) -> {
            if (old == null || now.isAfter(old.windowStart().plus(WINDOW))) {
                return new Requests(1, now, now);
            }
            if (old.count() >= MAX_PER_WINDOW) {
                throw new TooManyRequestsException("Too many codes requested for this email. Please try again later.");
            }
            if (now.isBefore(old.last().plus(COOLDOWN))) {
                throw new TooManyRequestsException("A code was just sent. Please wait a minute before asking for another.");
            }
            return new Requests(old.count() + 1, old.windowStart(), now);
        });
    }
}
