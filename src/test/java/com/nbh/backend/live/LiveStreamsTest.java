package com.nbh.backend.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LiveStreams} without a servlet container: a plain {@link SseEmitter} accepts a write with no
 * exception as long as it has not completed, whether or not anything is really listening on the other end
 * (see {@code ResponseBodyEmitter}'s own javadoc on early sends), so what is testable here — and what
 * actually matters — is the bookkeeping: how many streams are open, the caps that stop one order or one
 * server being flooded with connections, and that a closed connection is forgotten.
 */
class LiveStreamsTest {

    private final LiveStreams streams = new LiveStreams(new SyncTaskExecutor(), new ObjectMapper());

    @Test
    void subscribingCountsAsOneOpenStream() {
        streams.subscribeStaff();

        assertThat(streams.staffStreams()).isEqualTo(1);
    }

    /**
     * There is no way, in a bare unit test with no real servlet container, to make a connection look
     * "closed by the browser": {@code SseEmitter}'s completion callback only fires once Spring's async web
     * request machinery is driving it (proven the other way in {@code LiveEventStreamIntegrationTest}). What
     * this CAN prove — and what matters just as much in production, since a server cannot otherwise tell a
     * network drop happened until it tries to write — is the other half of the same cleanup path: a write
     * that fails because the connection is already done removes it, on the very next attempt.
     */
    @Test
    void aConnectionThatCanNoLongerBeWrittenToIsForgottenOnTheNextAttempt() {
        SseEmitter emitter = streams.subscribeStaff();
        emitter.complete(); // marks it done, so the NEXT send() throws — see the class javadoc above

        streams.publishToStaff(AdminEvent.of(AdminEvent.Type.ORDER_PLACED, "SO-1"));

        assertThat(streams.staffStreams()).isEqualTo(0);
    }

    @Test
    void publishingToStaffWithNoOneWatchingDoesNothing() {
        assertThatCode(() -> streams.publishToStaff(AdminEvent.of(AdminEvent.Type.ORDER_PLACED, "SO-1")))
                .doesNotThrowAnyException();
    }

    @Test
    void publishingToStaffAfterSubscribingNeverThrowsAndLeavesTheStreamOpen() {
        streams.subscribeStaff();

        assertThatCode(() -> streams.publishToStaff(
                new AdminEvent(AdminEvent.Type.PAYMENT_CONFIRMED, "SO-1", new BigDecimal("500"))))
                .doesNotThrowAnyException();
        assertThat(streams.staffStreams()).isEqualTo(1);
    }

    @Test
    void tooManyStaffStreamsAreRefused() {
        for (int i = 0; i < LiveStreams.MAX_STAFF_STREAMS; i++) {
            streams.subscribeStaff();
        }
        assertThat(streams.staffStreams()).isEqualTo(LiveStreams.MAX_STAFF_STREAMS);

        assertThatThrownBy(streams::subscribeStaff).isInstanceOf(TooManyRequestsException.class);
        assertThat(streams.staffStreams()).as("the refused attempt did not open a stream").isEqualTo(LiveStreams.MAX_STAFF_STREAMS);
    }

    @Test
    void anOrderCanBeWatchedFromSeveralPlacesButNotUnboundedly() {
        for (int i = 0; i < LiveStreams.MAX_STREAMS_PER_ORDER; i++) {
            streams.subscribeOrder("SO-1");
        }
        assertThat(streams.customerStreams()).isEqualTo(LiveStreams.MAX_STREAMS_PER_ORDER);

        assertThatThrownBy(() -> streams.subscribeOrder("SO-1")).isInstanceOf(TooManyRequestsException.class);
        // A different order is unaffected — the cap is per order, not global at this size.
        assertThatCode(() -> streams.subscribeOrder("SO-2")).doesNotThrowAnyException();
    }

    @Test
    void watchingAnOrderNeverTellsAnyoneWatchingADifferentOrder() {
        streams.subscribeOrder("SO-1");
        streams.subscribeOrder("SO-2");

        // Would throw (java.io.IOException wrapped as an assertion failure) if this reached SO-2's
        // emitter, since that one was never told to expect anything in this test.
        assertThatCode(() -> streams.publishToOrder("SO-1")).doesNotThrowAnyException();
        assertThat(streams.customerStreams()).isEqualTo(2);
    }

    /** Same mechanism as {@link #aConnectionThatCanNoLongerBeWrittenToIsForgottenOnTheNextAttempt}, for an order's watchers. */
    @Test
    void aWatcherThatCanNoLongerBeWrittenToFreesItsSlotForNewWatchers() {
        SseEmitter emitter = streams.subscribeOrder("SO-1");
        emitter.complete();

        streams.publishToOrder("SO-1");

        assertThat(streams.customerStreams()).isEqualTo(0);
        // If the dead watcher still counted against SO-1's cap, this loop would throw partway through.
        for (int i = 0; i < LiveStreams.MAX_STREAMS_PER_ORDER; i++) {
            assertThatCode(() -> streams.subscribeOrder("SO-1")).doesNotThrowAnyException();
        }
    }

    @Test
    void theHeartbeatNeverThrowsWhetherOrNotAnyoneIsListening() {
        assertThatCode(streams::heartbeat).doesNotThrowAnyException();

        streams.subscribeStaff();
        streams.subscribeOrder("SO-1");
        assertThatCode(streams::heartbeat).doesNotThrowAnyException();
        assertThat(streams.staffStreams()).isEqualTo(1);
        assertThat(streams.customerStreams()).isEqualTo(1);
    }
}
