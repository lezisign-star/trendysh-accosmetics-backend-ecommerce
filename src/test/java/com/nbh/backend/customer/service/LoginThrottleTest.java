package com.nbh.backend.customer.service;

import com.nbh.backend.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleTest {

    /** A clock the test can move forward. */
    private static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final LoginThrottle throttle = new LoginThrottle(clock);

    private void fail(String email, int times) {
        for (int i = 0; i < times; i++) throttle.recordFailure(email);
    }

    @Test
    void lockoutStartsAtTheFifthFailureAndIsPerEmailIgnoringCase() {
        fail("a@example.com", 4);
        assertThatCode(() -> throttle.checkAllowed("a@example.com")).doesNotThrowAnyException();

        fail("A@Example.com", 1);
        assertThatThrownBy(() -> throttle.checkAllowed("a@example.com")).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> throttle.checkAllowed("someone-else@example.com")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        fail("a@example.com", 4);
        throttle.reset("a@example.com");
        fail("a@example.com", 4);

        assertThatCode(() -> throttle.checkAllowed("a@example.com")).doesNotThrowAnyException();
    }

    @Test
    void aStaffSignInIsKeptApartFromAShopperWithTheSameEmail() {
        fail(LoginThrottle.staffKey("owner@example.com"), 5);

        assertThatThrownBy(() -> throttle.checkAllowed(LoginThrottle.staffKey("Owner@Example.com")))
                .isInstanceOf(TooManyRequestsException.class);
        // The shopper account that happens to share the address is not locked out by someone guessing the staff password.
        assertThatCode(() -> throttle.checkAllowed("owner@example.com")).doesNotThrowAnyException();
    }

    @Test
    void aFloodOfMadeUpEmailsCannotGrowTheMemoryForeverBecauseLapsedEntriesAreSwept() {
        for (int i = 0; i < LoginThrottle.SWEEP_ABOVE + 5; i++) {
            throttle.recordFailure("bot" + i + "@example.com");
        }
        clock.now = clock.now.plus(LoginThrottle.WINDOW).plusSeconds(1);

        throttle.recordFailure("next-one@example.com");

        java.util.Map<?, ?> remembered = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(throttle, "byEmail");
        assertThat(remembered.size()).isLessThan(10);
    }

    @Test
    void theLockoutEndsOnceTheWindowHasPassed() {
        fail("a@example.com", 5);
        assertThatThrownBy(() -> throttle.checkAllowed("a@example.com")).isInstanceOf(TooManyRequestsException.class);

        clock.now = clock.now.plus(LoginThrottle.WINDOW).plusSeconds(1);

        assertThatCode(() -> throttle.checkAllowed("a@example.com")).doesNotThrowAnyException();
    }
}
