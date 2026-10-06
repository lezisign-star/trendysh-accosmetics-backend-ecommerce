package com.nbh.backend.customer.service;

import com.nbh.backend.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing: after {@value #MAX_FAILURES} failed sign-ins for
 * one email inside {@link #WINDOW}, further attempts are refused until the window
 * passes. In-memory and per-instance, which is enough while the backend runs as a
 * single instance; a shared store would be needed to scale out.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    /** Above this many remembered emails, lapsed entries are swept so a flood of made-up emails can't grow the map for good. */
    static final int SWEEP_ABOVE = 10_000;

    private record Attempts(int failures, Instant windowStart) {}

    /** The key for a staff sign-in: kept apart from a shopper with the same email, so one can't lock out the other. */
    public static String staffKey(String email) {
        return "staff:" + (email == null ? "" : email);
    }

    private final ConcurrentHashMap<String, Attempts> byEmail = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Throws if this email is currently locked out. */
    public void checkAllowed(String email) {
        Attempts attempts = byEmail.get(key(email));
        if (attempts == null) {
            return;
        }
        if (expired(attempts)) {
            byEmail.remove(key(email));
            return;
        }
        if (attempts.failures() >= MAX_FAILURES) {
            throw new TooManyRequestsException("Too many failed sign-in attempts. Please wait a few minutes or reset your password.");
        }
    }

    public void recordFailure(String email) {
        Instant now = Instant.now(clock);
        byEmail.merge(key(email), new Attempts(1, now),
                (old, ignored) -> expired(old) ? new Attempts(1, now) : new Attempts(old.failures() + 1, old.windowStart()));
        if (byEmail.size() > SWEEP_ABOVE) {
            byEmail.entrySet().removeIf(entry -> expired(entry.getValue()));
        }
    }

    public void reset(String email) {
        byEmail.remove(key(email));
    }

    private boolean expired(Attempts attempts) {
        return Instant.now(clock).isAfter(attempts.windowStart().plus(WINDOW));
    }

    private static String key(String email) {
        return email.trim().toLowerCase();
    }
}
