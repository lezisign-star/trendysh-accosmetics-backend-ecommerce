package com.nbh.backend.live;

import com.nbh.backend.common.event.DataChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * The anonymous storefront stream over real HTTP, with room for exactly one visitor: what a browser is
 * actually sent when it connects, when something changes, and when the server is full. {@link
 * LiveStreamsTest} proves the bookkeeping on its own; this proves the bytes on the wire, which is where a
 * browser's {@code EventSource} decides whether to come back.
 *
 * <p>Runs in the suite's shared Spring context rather than its own: every distinct context keeps a database
 * connection pool open for the rest of the run, and Postgres only has so many connections to give. So the
 * visitor budget is narrowed to one for the duration of the test and put back afterwards. (The scheduled
 * flush that tells visitors about changes is held off in {@code application-test.yml}, so this test decides
 * exactly when that happens.) One scenario in one test, because the single slot is shared state: connect,
 * hear changes, then find the server full.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicEventStreamIntegrationTest {

    private static final Pattern RETRY = Pattern.compile("retry:(\\d+)");

    @Autowired private MockMvc mvc;
    @Autowired private LiveStreams streams;
    @Autowired private LiveUpdateListener listener;
    @Autowired private ApplicationEventPublisher events;

    private int originalBudget;

    @BeforeEach
    void leaveRoomForExactlyOneVisitor() {
        originalBudget = (int) ReflectionTestUtils.getField(streams, "maxVisitorStreams");
        ReflectionTestUtils.setField(streams, "maxVisitorStreams", streams.visitorStreams() + 1);
    }

    @AfterEach
    void restoreTheVisitorBudget() {
        ReflectionTestUtils.setField(streams, "maxVisitorStreams", originalBudget);
    }

    private static long retryMs(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        Matcher m = RETRY.matcher(body);
        assertThat(m.find()).as("a retry hint in: " + body).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    void aVisitorIsToldHowLongToWaitHearsOnlyPublicChangesAndAFullServerShedsRatherThanErrors() throws Exception {
        int before = streams.visitorStreams();

        // --- connect ---
        MvcResult visitor = mvc.perform(get("/api/v1/public/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(visitor.getResponse().getContentAsString()).contains(":connected");
        assertThat(retryMs(visitor)).as("random per visitor, so a restart does not bring everyone back at once")
                .isBetween(LiveStreams.VISITOR_RECONNECT_MIN_MS, LiveStreams.VISITOR_RECONNECT_MAX_MS);
        assertThat(streams.visitorStreams()).isEqualTo(before + 1);

        // --- a change a shopper cares about arrives, once, however many writes made it ---
        for (int i = 0; i < 25; i++) {
            events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        }
        listener.flushPublicDataChanges();
        String heard = visitor.getResponse().getContentAsString();
        assertThat(heard).contains("{\"type\":\"DATA_CHANGED\",\"topic\":\"PRODUCTS\"}");
        assertThat(heard.split("\"topic\":\"PRODUCTS\"", -1).length - 1)
                .as("25 writes, one hint").isEqualTo(1);

        // --- staff-only changes never reach a visitor ---
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.DELIVERY_SUGGESTIONS));
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
        listener.flushPublicDataChanges();
        assertThat(visitor.getResponse().getContentAsString()).doesNotContain("DELIVERY_SUGGESTIONS", "IMAGE_CLEANUP");

        // --- the server is full: the next visitor is told to come back in a minute or more, not given an error ---
        MvcResult shed = mvc.perform(get("/api/v1/public/events").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        assertThat(shed.getResponse().getStatus()).as("not a 429: EventSource gives up for good on an HTTP error")
                .isEqualTo(200);
        assertThat(shed.getResponse().getContentAsString()).contains(":busy");
        assertThat(retryMs(shed)).isBetween(LiveStreams.SHED_RETRY_MIN_MS, LiveStreams.SHED_RETRY_MAX_MS);
        assertThat(streams.visitorStreams()).as("the shed visitor holds no slot").isEqualTo(before + 1);
    }
}
