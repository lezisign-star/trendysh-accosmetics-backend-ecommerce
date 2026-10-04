package com.nbh.backend.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.exception.TooManyRequestsException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The open server-sent-event connections, and the way to write to them: staff dashboards (told about every
 * new order, payment and delivery update) and customers watching one order (told only THAT it changed,
 * never what changed, so nothing about an order leaks beyond the "changed" hint). This is how new orders and
 * payments show up on screen without anyone refreshing.
 *
 * <p>Connections live in memory in this one server process, which is right for a single instance (this
 * deployment); a second instance would need a shared broker. Every stream is capped, ends after 30
 * minutes (browsers reconnect by themselves) and gets a keep-alive comment every 20 seconds so a proxy
 * does not close it as idle. Writes go through {@code liveEventExecutor}, never the calling thread, and a
 * connection that fails a write is dropped.
 *
 * <p>Three separate budgets, so one kind of viewer can never crowd out another: staff, anonymous storefront
 * visitors (the cheap, lowest-priority kind), and people watching an order (a shopper waiting on their
 * payment). A visitor over budget is not refused with an error — a browser's {@code EventSource} gives up
 * for good on an HTTP error — it is told to come back in a minute or three and the stream ends cleanly, so
 * the live feature heals by itself once there is room. Every visitor stream also carries a random
 * reconnect delay, so a server restart does not bring every browser back in the same second.
 */
@Slf4j
@Component
public class LiveStreams {

    static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    static final int MAX_STAFF_STREAMS = 100;
    static final int DEFAULT_MAX_VISITOR_STREAMS = 1500;
    static final int DEFAULT_MAX_ORDER_STREAMS = 500;
    static final int MAX_STREAMS_PER_ORDER = 5;
    /** The reconnect delay each visitor's browser is told to use (random per connection, to spread a stampede). */
    static final long VISITOR_RECONNECT_MIN_MS = 5_000;
    static final long VISITOR_RECONNECT_MAX_MS = 25_000;
    /** How long an over-budget visitor is told to wait before trying again. */
    static final long SHED_RETRY_MIN_MS = 60_000;
    static final long SHED_RETRY_MAX_MS = 180_000;

    private final Set<SseEmitter> staff = ConcurrentHashMap.newKeySet();
    /** Anonymous storefront visitors, told only which topic changed. */
    private final Set<SseEmitter> visitors = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<SseEmitter>> byOrder = new ConcurrentHashMap<>();
    private final Executor executor;
    private final ObjectMapper json;
    private final int maxVisitorStreams;
    private final int maxOrderStreams;
    private volatile Counter shedCounter;

    @Autowired
    public LiveStreams(@Qualifier("liveEventExecutor") Executor executor, ObjectMapper json,
                       @Value("${app.live.max-visitor-streams:" + DEFAULT_MAX_VISITOR_STREAMS + "}") int maxVisitorStreams,
                       @Value("${app.live.max-order-streams:" + DEFAULT_MAX_ORDER_STREAMS + "}") int maxOrderStreams) {
        this.executor = executor;
        this.json = json;
        this.maxVisitorStreams = maxVisitorStreams;
        this.maxOrderStreams = maxOrderStreams;
    }

    /** The default budgets — what a unit test without a Spring context uses. */
    public LiveStreams(Executor executor, ObjectMapper json) {
        this(executor, json, DEFAULT_MAX_VISITOR_STREAMS, DEFAULT_MAX_ORDER_STREAMS);
    }

    /**
     * How full each budget is ({@code live.streams.open{kind=staff|visitor|order}}) and how many visitors were
     * told to come back later ({@code live.visitor.shed}) — so a busy day shows up on the metrics instead of as
     * a silent loss of live updates. Optional: without a registry (a plain unit test) nothing is recorded.
     */
    @Autowired(required = false)
    void registerMetrics(MeterRegistry registry) {
        Gauge.builder("live.streams.open", staff, Set::size).tag("kind", "staff").register(registry);
        Gauge.builder("live.streams.open", visitors, Set::size).tag("kind", "visitor").register(registry);
        Gauge.builder("live.streams.open", this, LiveStreams::orderStreams).tag("kind", "order").register(registry);
        this.shedCounter = Counter.builder("live.visitor.shed")
                .description("Storefront visitors told to retry later because the visitor stream budget was full")
                .register(registry);
    }

    // ---- connecting ----

    public SseEmitter subscribeStaff() {
        if (staff.size() >= MAX_STAFF_STREAMS) {
            throw new TooManyRequestsException("Too many live views are open. Close one and try again.");
        }
        SseEmitter emitter = newEmitter();
        staff.add(emitter);
        onClosed(emitter, () -> staff.remove(emitter));
        greet(emitter);
        return emitter;
    }

    /**
     * A storefront visitor. Never throws for being over budget: see the class comment — the stream just ends
     * cleanly with a long reconnect delay, and the page carries on without live updates until then.
     */
    public SseEmitter subscribePublic() {
        if (visitors.size() >= maxVisitorStreams) {
            return shed();
        }
        SseEmitter emitter = newEmitter();
        visitors.add(emitter);
        onClosed(emitter, () -> visitors.remove(emitter));
        greet(emitter, randomBetween(VISITOR_RECONNECT_MIN_MS, VISITOR_RECONNECT_MAX_MS));
        return emitter;
    }

    /** {@code orderNo} must be the normalised number of an order that exists: the caller has checked. */
    public SseEmitter subscribeOrder(String orderNo) {
        if (orderStreams() >= maxOrderStreams) {
            throw new TooManyRequestsException("Too many people are watching orders right now. Please try again shortly.");
        }
        Set<SseEmitter> watchers = byOrder.computeIfAbsent(orderNo, key -> ConcurrentHashMap.newKeySet());
        if (watchers.size() >= MAX_STREAMS_PER_ORDER) {
            throw new TooManyRequestsException("This order is already open in several places.");
        }
        SseEmitter emitter = newEmitter();
        watchers.add(emitter);
        onClosed(emitter, () -> watchers.remove(emitter));
        greet(emitter);
        return emitter;
    }

    // ---- publishing ----

    public void publishToStaff(AdminEvent event) {
        if (staff.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("Could not write a live update: {}", e.getMessage());
            return;
        }
        dispatch(() -> deliver(staff, () -> SseEmitter.event().data(payload)));
    }

    /** Tells every open storefront that a public topic changed (e.g. PRODUCTS). */
    public void publishToPublic(String topic) {
        if (visitors.isEmpty()) {
            return;
        }
        String payload = "{\"type\":\"DATA_CHANGED\",\"topic\":\"" + topic + "\"}";
        dispatch(() -> deliver(visitors, () -> SseEmitter.event().data(payload)));
    }

    /** Tells whoever is watching this order that something about it changed. */
    public void publishToOrder(String orderNo) {
        Set<SseEmitter> watchers = orderNo == null ? null : byOrder.get(orderNo);
        if (watchers == null || watchers.isEmpty()) {
            return;
        }
        dispatch(() -> deliver(watchers, () -> SseEmitter.event().data("{\"type\":\"CHANGED\"}")));
    }

    @Scheduled(fixedRateString = "${app.live.heartbeat-ms:20000}", initialDelayString = "${app.live.heartbeat-ms:20000}")
    public void heartbeat() {
        byOrder.values().removeIf(Set::isEmpty);
        if (staff.isEmpty() && byOrder.isEmpty() && visitors.isEmpty()) {
            return;
        }
        dispatch(() -> {
            deliver(staff, () -> SseEmitter.event().comment("keep-alive"));
            deliver(visitors, () -> SseEmitter.event().comment("keep-alive"));
            for (Set<SseEmitter> watchers : List.copyOf(byOrder.values())) {
                deliver(watchers, () -> SseEmitter.event().comment("keep-alive"));
            }
        });
    }

    // ---- how many are open ----

    public int staffStreams() {
        return staff.size();
    }

    public int visitorStreams() {
        return visitors.size();
    }

    public int orderStreams() {
        return byOrder.values().stream().mapToInt(Set::size).sum();
    }

    public int customerStreams() {
        return orderStreams() + visitorStreams();
    }

    // ---- plumbing ----

    private static SseEmitter newEmitter() {
        return new SseEmitter(TIMEOUT_MS);
    }

    private static long randomBetween(long minInclusive, long maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1);
    }

    /**
     * A stream that says "busy, come back in a minute or three" and ends. The browser waits the stated delay
     * and reconnects by itself, which an HTTP error would not get it to do.
     */
    private SseEmitter shed() {
        Counter counter = shedCounter;
        if (counter != null) {
            counter.increment();
        }
        SseEmitter emitter = newEmitter();
        try {
            emitter.send(SseEmitter.event().reconnectTime(randomBetween(SHED_RETRY_MIN_MS, SHED_RETRY_MAX_MS))
                    .comment("busy"));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** Whatever ends a connection (finished, timed out, failed) takes it out of the lists. */
    private static void onClosed(SseEmitter emitter, Runnable forget) {
        emitter.onCompletion(forget);
        emitter.onTimeout(() -> {
            forget.run();
            emitter.complete();
        });
        emitter.onError(error -> forget.run());
    }

    /** An immediate first write, so the browser's request resolves and it knows the stream is open. */
    private static void greet(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    /** As {@link #greet(SseEmitter)}, also telling the browser how long to wait before it reconnects if this stream drops. */
    private static void greet(SseEmitter emitter, long reconnectMs) {
        try {
            emitter.send(SseEmitter.event().reconnectTime(reconnectMs).comment("connected"));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private void dispatch(Runnable work) {
        try {
            executor.execute(work);
        } catch (RejectedExecutionException e) {
            log.debug("Live update dropped: the writer queue is full");
        }
    }

    /** A message is built afresh for each connection: an event builder is single use. */
    private static void deliver(Set<SseEmitter> targets, Supplier<SseEmitter.SseEventBuilder> message) {
        for (SseEmitter emitter : List.copyOf(targets)) {
            try {
                emitter.send(message.get());
            } catch (IOException | IllegalStateException e) {
                targets.remove(emitter);
                // A browser that has gone away ends up here. The framework then reports it as an error
                // dispatch; GlobalExceptionHandler.handleClientGone keeps that quiet.
                emitter.completeWithError(e);
            }
        }
    }
}
