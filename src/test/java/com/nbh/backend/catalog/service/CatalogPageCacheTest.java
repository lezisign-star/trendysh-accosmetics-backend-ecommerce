package com.nbh.backend.catalog.service;

import com.nbh.backend.common.event.DataChangedEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogPageCache} on its own, with a clock the test moves by hand: when a built page is reused,
 * when it must not be, that a crowd arriving together costs one build, and that memory stays bounded.
 */
class CatalogPageCacheTest {

    /** A clock that only moves when told to. */
    private static final class ManualClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000);

        void advance(long ms) {
            millis.addAndGet(ms);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
    }

    private static final long TTL = 10_000;

    private final ManualClock clock = new ManualClock();
    private final CatalogPageCache cache = new CatalogPageCache(TTL, clock);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A loader that counts how many times it was asked to build, returning "page-N". */
    private static final class CountingLoader implements java.util.function.Supplier<byte[]> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public byte[] get() {
            return bytes("page-" + calls.incrementAndGet());
        }
    }

    @Test
    void aSecondRequestForTheSamePageIsServedFromMemory() {
        CountingLoader loader = new CountingLoader();

        CatalogPageCache.Cached first = cache.get("a", loader);
        CatalogPageCache.Cached second = cache.get("a", loader);

        assertThat(loader.calls).hasValue(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    void differentPagesAreCachedSeparately() {
        CountingLoader loader = new CountingLoader();

        CatalogPageCache.Cached a = cache.get("a", loader);
        CatalogPageCache.Cached b = cache.get("b", loader);

        assertThat(loader.calls).hasValue(2);
        assertThat(a.etag()).isNotEqualTo(b.etag());
    }

    @Test
    void aPageIsRebuiltOnceItsTimeIsUp() {
        CountingLoader loader = new CountingLoader();
        cache.get("a", loader);

        clock.advance(TTL - 1);
        cache.get("a", loader);
        assertThat(loader.calls).as("still fresh a millisecond before the limit").hasValue(1);

        clock.advance(1);
        CatalogPageCache.Cached rebuilt = cache.get("a", loader);
        assertThat(loader.calls).hasValue(2);
        assertThat(new String(rebuilt.body(), StandardCharsets.UTF_8)).isEqualTo("page-2");
    }

    @Test
    void aProductCategoryOrStockChangeDropsEveryCachedPageAtOnce() {
        for (DataChangedEvent.Topic topic : List.of(DataChangedEvent.Topic.PRODUCTS,
                DataChangedEvent.Topic.CATALOG, DataChangedEvent.Topic.STOCK)) {
            CatalogPageCache fresh = new CatalogPageCache(TTL, clock); // a new cache per topic, so none carries over
            CountingLoader loader = new CountingLoader();
            fresh.get("a", loader);
            fresh.get("b", loader);
            assertThat(loader.calls).hasValue(2);

            fresh.onDataChanged(new DataChangedEvent(topic));

            fresh.get("a", loader);
            fresh.get("b", loader);
            assertThat(loader.calls).as("both rebuilt after a " + topic + " change").hasValue(4);
        }
    }

    @Test
    void changesThatAreNotOnACataloguePageLeaveTheCacheAlone() {
        CountingLoader loader = new CountingLoader();
        cache.get("a", loader);

        cache.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.DELIVERY_PLACES));
        cache.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.DELIVERY_SUGGESTIONS));
        cache.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
        cache.get("a", loader);

        assertThat(loader.calls).hasValue(1);
    }

    /** The race that would otherwise serve a page that was stale the moment it was stored. */
    @Test
    void aWriteThatLandsWhileAPageIsBeingBuiltDoesNotLeaveThatPageServedAsCurrent() {
        AtomicInteger builds = new AtomicInteger();
        java.util.function.Supplier<byte[]> writeLandsMidBuild = () -> {
            int n = builds.incrementAndGet();
            if (n == 1) {
                cache.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.STOCK));
            }
            return bytes("build-" + n);
        };

        cache.get("a", writeLandsMidBuild);
        CatalogPageCache.Cached next = cache.get("a", writeLandsMidBuild);

        assertThat(builds).as("the first build was already out of date, so the next request builds again").hasValue(2);
        assertThat(new String(next.body(), StandardCharsets.UTF_8)).isEqualTo("build-2");
    }

    @Test
    void aTimeToLiveOfZeroSwitchesCachingOffButStillGivesAValidator() {
        CatalogPageCache off = new CatalogPageCache(0, clock);
        CountingLoader loader = new CountingLoader();

        CatalogPageCache.Cached one = off.get("a", loader);
        CatalogPageCache.Cached two = off.get("a", loader);

        assertThat(loader.calls).hasValue(2);
        assertThat(one.etag()).startsWith("W/\"").endsWith("\"");
        assertThat(one.etag()).isNotEqualTo(two.etag()); // different bytes, different validator
        assertThat(off.size()).isZero();
    }

    @Test
    void theValidatorIsWeakAndFollowsTheContent() {
        CatalogPageCache.Cached x1 = cache.get("x", () -> bytes("same bytes"));
        CatalogPageCache.Cached x2 = new CatalogPageCache(TTL, clock).get("anything", () -> bytes("same bytes"));
        CatalogPageCache.Cached y = cache.get("y", () -> bytes("other bytes"));

        assertThat(x1.etag()).as("weak, so gzip on the wire does not invalidate it").startsWith("W/\"");
        assertThat(x1.etag()).as("same content, same validator, whichever page or cache built it").isEqualTo(x2.etag());
        assertThat(x1.etag()).isNotEqualTo(y.etag());
    }

    @Test
    void aCrowdArrivingTogetherCostsOneBuild() throws Exception {
        int callers = 24;
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<CatalogPageCache.Cached>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.get("hot", () -> {
                        builds.incrementAndGet();
                        try {
                            Thread.sleep(150); // a slow database, so everyone else has time to pile up behind it
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return bytes("the page");
                    });
                }));
            }
            start.countDown();
            for (Future<CatalogPageCache.Cached> result : results) {
                assertThat(new String(result.get(10, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8)).isEqualTo("the page");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(builds).as("everyone got the page, but the database was asked once").hasValue(1);
    }

    // ---- failing and slow builds: the cache must never make the busiest endpoint worse than having no cache ----

    @Test
    void aFailedBuildLeavesNothingBehindAndTheNextRequestBuildsAfresh() {
        for (int i = 0; i < 200; i++) {
            int n = i;
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> cache.get("bad-" + n, () -> { throw new IllegalArgumentException("no such sort"); }));
        }

        assertThat(cache.buildsInFlight()).as("no lock or in-flight marker left per failed page").isZero();
        assertThat(cache.size()).isZero();
        CountingLoader loader = new CountingLoader();
        assertThat(new String(cache.get("bad-0", loader).body(), StandardCharsets.UTF_8)).isEqualTo("page-1");
    }

    @Test
    void everyoneWaitingOnAFailedBuildGetsTheFailureAtOnceInsteadOfRetryingOneByOne() throws Exception {
        int callers = 8;
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<CatalogPageCache.Cached>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.get("doomed", () -> {
                        builds.incrementAndGet();
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IllegalStateException("database down");
                    });
                }));
            }
            start.countDown();
            for (Future<CatalogPageCache.Cached> result : results) {
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(builds).as("one failed build, shared - not eight in a row").hasValue(1);
        assertThat(cache.buildsInFlight()).isZero();
    }

    /** Runs {@code loader} on its own thread, returning once it has started and is blocked on {@code release}. */
    private static Thread startABuildThatBlocksUntil(CatalogPageCache target, String key, CountDownLatch release)
            throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        Thread leader = new Thread(() -> target.get(key, () -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return bytes("leader's page");
        }));
        leader.start();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        return leader;
    }

    @Test
    void aSlowBuildDoesNotHoldEveryoneUpTheLastPageHeldIsServedInstead() throws Exception {
        CatalogPageCache impatient = new CatalogPageCache(TTL, clock, 100, CatalogPageCache.DEFAULT_MAX_BYTES);
        impatient.get("a", () -> bytes("old page"));
        clock.advance(TTL + 1); // expired, but still held for exactly this situation
        CountDownLatch release = new CountDownLatch(1);
        Thread leader = startABuildThatBlocksUntil(impatient, "a", release);
        try {
            long started = System.nanoTime();
            CatalogPageCache.Cached served = impatient.get("a", () -> bytes("should never be built"));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(new String(served.body(), StandardCharsets.UTF_8)).isEqualTo("old page");
            assertThat(waitedMs).as("waited for the build only briefly, not until it finished").isLessThan(5_000);
        } finally {
            release.countDown();
            leader.join(10_000);
        }
        assertThat(impatient.buildsInFlight()).isZero();
    }

    @Test
    void withNoPageHeldASlowBuildIsNotWaitedOnForeverEitherARequestBuildsItsOwn() throws Exception {
        CatalogPageCache impatient = new CatalogPageCache(TTL, clock, 100, CatalogPageCache.DEFAULT_MAX_BYTES);
        CountDownLatch release = new CountDownLatch(1);
        Thread leader = startABuildThatBlocksUntil(impatient, "fresh", release);
        try {
            CatalogPageCache.Cached served = impatient.get("fresh", () -> bytes("my own build"));

            assertThat(new String(served.body(), StandardCharsets.UTF_8)).isEqualTo("my own build");
        } finally {
            release.countDown();
            leader.join(10_000);
        }
    }

    @Test
    void memoryIsBoundedInBytesAsWellAsInPageCount() {
        CatalogPageCache small = new CatalogPageCache(TTL, clock, 3_000, 1_000);
        for (int i = 0; i < 20; i++) {
            small.get("page-" + i, () -> new byte[200]);
            clock.advance(1);
        }
        assertThat(small.bytesHeld()).isLessThanOrEqualTo(1_000);
        assertThat(small.size()).isLessThan(20);

        // A page that alone is more than a quarter of the budget is not worth holding at all.
        CountingLoader again = new CountingLoader();
        small.get("huge", () -> { again.calls.incrementAndGet(); return new byte[300]; });
        small.get("huge", () -> { again.calls.incrementAndGet(); return new byte[300]; });
        assertThat(again.calls).as("not kept, so built again").hasValue(2);
        assertThat(small.bytesHeld()).isLessThanOrEqualTo(1_000);
    }

    @Test
    void pagesThatExpiredLongAgoAreSweptNotKeptForever() {
        cache.get("a", () -> bytes("x"));
        clock.advance(TTL + CatalogPageCache.STALE_GRACE_MS + 1);

        cache.get("b", () -> bytes("y"));

        assertThat(cache.size()).as("'a' expired past its grace period and was dropped when 'b' was stored").isEqualTo(1);
    }

    @Test
    void memoryStaysBoundedHoweverManyDifferentPagesAreAskedFor() {
        for (int i = 0; i < CatalogPageCache.MAX_ENTRIES * 3; i++) {
            cache.get("category-" + i, () -> bytes("x"));
            clock.advance(1);
        }

        assertThat(cache.size()).isLessThanOrEqualTo(CatalogPageCache.MAX_ENTRIES);
    }

    @Test
    void whenFullTheStalestPageIsTheOneDropped() {
        for (int i = 0; i < CatalogPageCache.MAX_ENTRIES; i++) {
            cache.get("old-" + i, () -> bytes("x"));
            clock.advance(1);
        }
        CountingLoader loader = new CountingLoader();
        cache.get("newcomer", loader);

        // "old-0" was built first, so it expires first and is the one that made room.
        CountingLoader again = new CountingLoader();
        cache.get("old-0", again);
        cache.get("old-" + (CatalogPageCache.MAX_ENTRIES - 1), again);

        assertThat(again.calls).as("old-0 was dropped (rebuilt), the most recent old page was kept").hasValue(1);
    }
}
