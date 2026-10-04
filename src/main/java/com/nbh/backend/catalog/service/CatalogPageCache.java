package com.nbh.backend.catalog.service;

import com.nbh.backend.common.event.DataChangedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A few seconds' memory of the storefront's catalogue pages, so a burst of identical requests costs the
 * database one computation instead of hundreds.
 *
 * <p>Why it exists: every product, category or stock change tells every open storefront to look again, and
 * each browser then asks for the whole catalogue. The pushes are already spread out and throttled on the
 * client, but a popular moment still sends many near-identical requests close together; without this each
 * one ran the full query plus its half-dozen lookups. Here the first request of a burst builds the page and
 * the rest share that one build ({@link #get}).
 *
 * <p>When a cached page stops being served as current:
 * <ul>
 *   <li>a product, category, promotion or stock change commits ({@link DataChangedEvent}) — the version
 *       moves on and every cached page is stale at once;</li>
 *   <li>{@code app.catalog.page-cache-ttl-ms} passes (default 10 s) — covers what no write announces: a deal
 *       or campaign ending by the clock, a review being approved, sales figures.</li>
 * </ul>
 *
 * <p>Built to fail safe, because it sits in front of the busiest public endpoint:
 * <ul>
 *   <li><b>Bounded memory.</b> At most {@value #MAX_ENTRIES} pages and {@code maxBytes} bytes in all (the
 *       stalest are dropped first); a page too big to be worth keeping is simply not kept. The caller only
 *       uses the cache for requests whose possible inputs are few (see the controller).</li>
 *   <li><b>No unbounded waiting.</b> Requests for a page that is being built wait for that one build, but
 *       only for {@code buildWaitMs}. If it is still not done (a struggling database), a request is served
 *       the last page held for it — slightly out of date beats hanging — or, with none, builds its own as it
 *       would have without the cache. Nothing queues forever behind a stuck build.</li>
 *   <li><b>Failures are shared, not repeated.</b> If the build fails, everyone waiting on it gets that same
 *       failure at once instead of retrying one after another, and nothing is left behind to leak.</li>
 * </ul>
 *
 * The stored body is the exact JSON bytes with a weak ETag, so a browser that already holds the same bytes
 * gets a bodiless 304. Set the TTL to 0 to switch caching off (the ETag still works; tests rely on that so a
 * direct database write is never hidden behind a cached page).
 */
@Component
public class CatalogPageCache {

    /** One cached page: the JSON bytes exactly as sent, and their validator. */
    public record Cached(byte[] body, String etag) {}

    private record Entry(long version, long expiresAtMs, Cached page) {}

    static final int MAX_ENTRIES = 64;
    static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;
    static final long DEFAULT_BUILD_WAIT_MS = 3_000;
    /** An expired page is kept this much longer, only to be served if the next build is slow. */
    static final long STALE_GRACE_MS = 60_000;

    private final long ttlMs;
    private final Clock clock;
    private final long buildWaitMs;
    private final long maxBytes;
    private final AtomicLong version = new AtomicLong();
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    /** Pages being built right now, so a crowd shares one build. Removed when the build ends, however it ends. */
    private final ConcurrentHashMap<String, CompletableFuture<Cached>> inFlight = new ConcurrentHashMap<>();
    private final Object storeLock = new Object();

    @Autowired
    public CatalogPageCache(@Value("${app.catalog.page-cache-ttl-ms:10000}") long ttlMs, Clock clock,
                            @Value("${app.catalog.page-cache-build-wait-ms:" + DEFAULT_BUILD_WAIT_MS + "}") long buildWaitMs) {
        this(ttlMs, clock, buildWaitMs, DEFAULT_MAX_BYTES);
    }

    /** Defaults for the waiting and memory limits — what the tests that don't care about them use. */
    CatalogPageCache(long ttlMs, Clock clock) {
        this(ttlMs, clock, DEFAULT_BUILD_WAIT_MS, DEFAULT_MAX_BYTES);
    }

    CatalogPageCache(long ttlMs, Clock clock, long buildWaitMs, long maxBytes) {
        this.ttlMs = ttlMs;
        this.clock = clock;
        this.buildWaitMs = buildWaitMs;
        this.maxBytes = maxBytes;
    }

    /** Called after a write that changes what the storefront shows commits. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDataChanged(DataChangedEvent event) {
        switch (event.topic()) {
            case PRODUCTS, CATALOG, STOCK -> invalidate();
            default -> { /* delivery places, suggestions, photo-cleanup progress: not on a catalogue page */ }
        }
    }

    /** Every cached page is stale from now on. */
    public void invalidate() {
        version.incrementAndGet();
    }

    /**
     * The page for {@code key}: from memory if there is a fresh copy, otherwise built by {@code loader}
     * (once, however many callers are waiting) and remembered.
     */
    public Cached get(String key, Supplier<byte[]> loader) {
        if (ttlMs <= 0) {
            return build(loader.get());
        }
        Entry held = entries.get(key);
        if (isFresh(held, version.get())) {
            return held.page();
        }
        CompletableFuture<Cached> mine = new CompletableFuture<>();
        CompletableFuture<Cached> building = inFlight.putIfAbsent(key, mine);
        return building == null ? buildAndShare(key, loader, mine) : waitFor(building, held, loader);
    }

    private Cached buildAndShare(String key, Supplier<byte[]> loader, CompletableFuture<Cached> mine) {
        try {
            // Read the version BEFORE loading: a write that commits mid-load then leaves this page already
            // stale, instead of it being stored as if it were current.
            long loadedAt = version.get();
            Entry justBuilt = entries.get(key); // a build may have finished between the caller's look and now
            if (isFresh(justBuilt, loadedAt)) {
                mine.complete(justBuilt.page());
                return justBuilt.page();
            }
            Cached page = build(loader.get());
            store(key, loadedAt, page);
            mine.complete(page);
            return page;
        } catch (RuntimeException | Error failure) {
            mine.completeExceptionally(failure); // everyone waiting on this build gets the failure at once
            throw failure;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private Cached waitFor(CompletableFuture<Cached> building, Entry held, Supplier<byte[]> loader) {
        try {
            return building.get(buildWaitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            // The build is taking too long (a struggling database). Don't queue behind it: serve the last page
            // held for this request, or with none build one just as it would have been without the cache.
            return held != null ? held.page() : build(loader.get());
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Couldn't build the catalogue page", cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the catalogue page", interrupted);
        }
    }

    private boolean isFresh(Entry entry, long currentVersion) {
        return entry != null && entry.version() == currentVersion && clock.millis() < entry.expiresAtMs();
    }

    /**
     * Keeps memory bounded however many distinct pages are asked for: forget what expired long ago, then drop
     * the stalest until the new page fits within both the page-count and the byte limits.
     */
    private void store(String key, long loadedAt, Cached page) {
        long size = page.body().length;
        if (size > maxBytes / 4) {
            return; // too big to be worth holding; the next request builds it again
        }
        synchronized (storeLock) {
            long now = clock.millis();
            entries.remove(key);
            entries.values().removeIf(e -> e.expiresAtMs() + STALE_GRACE_MS <= now);
            long held = entries.values().stream().mapToLong(e -> e.page().body().length).sum();
            while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES || held + size > maxBytes)) {
                var stalest = entries.entrySet().stream()
                        .min(Comparator.comparingLong(e -> e.getValue().expiresAtMs()))
                        .orElseThrow();
                held -= stalest.getValue().page().body().length;
                entries.remove(stalest.getKey());
            }
            entries.put(key, new Entry(loadedAt, now + ttlMs, page));
        }
    }

    /** Weak (the bytes may be gzip-encoded on the wire, which a strong tag would forbid) and derived from the content. */
    private static Cached build(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            String tag = Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 12));
            return new Cached(body, "W/\"" + tag + "\"");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For tests and diagnostics: how many pages are held right now. */
    int size() {
        return entries.size();
    }

    /** For tests and diagnostics: how many bytes of pages are held right now. */
    long bytesHeld() {
        return entries.values().stream().mapToLong(e -> e.page().body().length).sum();
    }

    /** For tests and diagnostics: how many pages are being built right now. */
    int buildsInFlight() {
        return inFlight.size();
    }
}
