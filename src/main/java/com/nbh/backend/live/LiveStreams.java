package com.nbh.backend.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
 */
@Slf4j
@Component
public class LiveStreams {

    static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    static final int MAX_STAFF_STREAMS = 100;
    static final int MAX_CUSTOMER_STREAMS = 1000;
    static final int MAX_STREAMS_PER_ORDER = 5;

    private final Set<SseEmitter> staff = ConcurrentHashMap.newKeySet();
    /** Anonymous storefront visitors, told only which topic changed. */
    private final Set<SseEmitter> visitors = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<SseEmitter>> byOrder = new ConcurrentHashMap<>();
    private final Executor executor;
    private final ObjectMapper json;

    public LiveStreams(@Qualifier("liveEventExecutor") Executor executor, ObjectMapper json) {
        this.executor = executor;
        this.json = json;
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

    public SseEmitter subscribePublic() {
        if (customerStreams() >= MAX_CUSTOMER_STREAMS) {
            throw new TooManyRequestsException("Too many people are connected right now. Please try again shortly.");
        }
        SseEmitter emitter = newEmitter();
        visitors.add(emitter);
        onClosed(emitter, () -> visitors.remove(emitter));
        greet(emitter);
        return emitter;
    }

    /** {@code orderNo} must be the normalised number of an order that exists: the caller has checked. */
    public SseEmitter subscribeOrder(String orderNo) {
        if (customerStreams() >= MAX_CUSTOMER_STREAMS) {
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

    public int customerStreams() {
        return byOrder.values().stream().mapToInt(Set::size).sum() + visitors.size();
    }

    // ---- plumbing ----

    private static SseEmitter newEmitter() {
        return new SseEmitter(TIMEOUT_MS);
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
                emitter.completeWithError(e);
            }
        }
    }
}
