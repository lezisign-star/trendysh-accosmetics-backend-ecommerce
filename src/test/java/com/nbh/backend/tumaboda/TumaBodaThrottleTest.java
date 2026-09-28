package com.nbh.backend.tumaboda;

import com.nbh.backend.common.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TumaBodaThrottleTest {

    private static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-09-28T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final TumaBodaThrottle throttle = new TumaBodaThrottle(clock);

    @Test
    void aCallerGetsTheirQuotesThenIsRefusedUntilTheMinuteTurns() {
        for (int i = 0; i < TumaBodaThrottle.QUOTES_PER_CALLER; i++) {
            throttle.quote("1.2.3.4");
        }

        assertThatThrownBy(() -> throttle.quote("1.2.3.4")).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> throttle.quote("5.6.7.8")).as("someone else").doesNotThrowAnyException();

        clock.now = clock.now.plusSeconds(60);
        assertThatCode(() -> throttle.quote("1.2.3.4")).as("next minute").doesNotThrowAnyException();
    }

    @Test
    void searchesAndQuotesAreCountedSeparately() {
        for (int i = 0; i < TumaBodaThrottle.QUOTES_PER_CALLER; i++) {
            throttle.quote("1.2.3.4");
        }

        assertThatCode(() -> throttle.lookup("1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void aCallerGetsRoomToTypeAnAddress() {
        for (int i = 0; i < TumaBodaThrottle.LOOKUPS_PER_CALLER; i++) {
            throttle.lookup("1.2.3.4");
        }

        assertThatThrownBy(() -> throttle.lookup("1.2.3.4")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void everyoneTogetherHasABackstopSoManyCallersCannotRunUpTheKey() {
        // 10 each is well within any one caller's limit, but 20 callers x 10 = 200 = the total.
        for (int caller = 0; caller < 20; caller++) {
            for (int i = 0; i < 10; i++) {
                throttle.quote("caller-" + caller);
            }
        }

        assertThatThrownBy(() -> throttle.quote("a-newcomer")).isInstanceOf(TooManyRequestsException.class);

        clock.now = clock.now.plusSeconds(60);
        assertThatCode(() -> throttle.quote("a-newcomer")).doesNotThrowAnyException();
    }

    @Test
    void aRefusedCallerDoesNotUseUpTheSharedBudget() {
        for (int i = 0; i < TumaBodaThrottle.QUOTES_PER_CALLER; i++) {
            throttle.quote("noisy");
        }
        for (int i = 0; i < 500; i++) {
            assertThatThrownBy(() -> throttle.quote("noisy")).isInstanceOf(TooManyRequestsException.class);
        }

        // "noisy" spent 15 of the 200 shared; the 500 refusals must have cost nothing, so exactly 185 remain.
        int left = TumaBodaThrottle.QUOTES_IN_TOTAL - TumaBodaThrottle.QUOTES_PER_CALLER;
        for (int i = 0; i < left; i++) {
            throttle.quote("caller-" + (i / 10));
        }
        assertThatThrownBy(() -> throttle.quote("one-too-many")).isInstanceOf(TooManyRequestsException.class);
    }

    // ---- who is asking ----

    private static HttpServletRequest request(String forwardedFor, String remoteAddr) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        Mockito.when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    @Test
    void behindAProxyTheCallerIsTheAddressTheProxyAddedNotWhatTheCallerClaimed() {
        // A caller can send any X-Forwarded-For they like; the proxy appends the real address at the end.
        assertThat(TumaBodaPublicController.caller(request("6.6.6.6, 203.0.113.9", "10.0.0.1"))).isEqualTo("203.0.113.9");
        assertThat(TumaBodaPublicController.caller(request("203.0.113.9", "10.0.0.1"))).isEqualTo("203.0.113.9");
    }

    @Test
    void withoutTheHeaderTheConnectionAddressIsUsed() {
        assertThat(TumaBodaPublicController.caller(request(null, "192.0.2.7"))).isEqualTo("192.0.2.7");
        assertThat(TumaBodaPublicController.caller(request("  ", "192.0.2.7"))).isEqualTo("192.0.2.7");
        assertThat(TumaBodaPublicController.caller(request("203.0.113.9,", "192.0.2.7"))).isEqualTo("203.0.113.9");
    }
}
