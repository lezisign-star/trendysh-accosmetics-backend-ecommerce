package com.nbh.backend.tumaboda;

import com.nbh.backend.tumaboda.TumaBodaDtos.PackageSpec;
import com.nbh.backend.tumaboda.TumaBodaDtos.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pricing goes through two rules that protect the shop rather than the customer: the price is a whole
 * number of shillings (M-PESA takes no cents, and rounding is always up), and only a few quote calls may
 * wait on TumaBoda at once because each one holds a database connection while it does.
 */
class TumaBodaQuoteServiceTest {

    private static final BigDecimal NAIROBI_LAT = new BigDecimal("-1.2864");
    private static final BigDecimal NAIROBI_LNG = new BigDecimal("36.8172");
    private static final BigDecimal BASKET = new BigDecimal("3000");

    private final TumaBodaClient client = mock(TumaBodaClient.class);
    private final TumaBodaConfig config =
            new TumaBodaConfig("key", "http://localhost/api", "secret", new BigDecimal("5000"), new BigDecimal("20000"));

    private static Quote priced(String totalKes) {
        return new Quote(new BigDecimal(totalKes), new BigDecimal("20"), new BigDecimal("4.2"), 18);
    }

    @Test
    void aPriceWithCentsIsRoundedUpToTheWholeShilling() {
        TumaBodaQuoteService service = new TumaBodaQuoteService(config, client);

        when(client.quote(any(), any(), any(PackageSpec.class))).thenReturn(priced("199.20"));
        assertThat(service.quote(NAIROBI_LAT, NAIROBI_LNG, BASKET).totalKes()).isEqualByComparingTo("200");

        when(client.quote(any(), any(), any(PackageSpec.class))).thenReturn(priced("150.01"));
        assertThat(service.quote(NAIROBI_LAT, NAIROBI_LNG, BASKET).totalKes()).isEqualByComparingTo("151");

        when(client.quote(any(), any(), any(PackageSpec.class))).thenReturn(priced("180.00"));
        assertThat(service.quote(NAIROBI_LAT, NAIROBI_LNG, BASKET).totalKes())
                .as("a price that is already whole is left alone")
                .isEqualByComparingTo("180");
    }

    @Test
    void onlyTheConfiguredNumberOfQuotesWaitOnTumaBodaAtOnce() throws Exception {
        TumaBodaQuoteService service = new TumaBodaQuoteService(config, client, 2, Duration.ofMillis(150));
        CountDownLatch bothInFlight = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(client.quote(any(), any(), any(PackageSpec.class))).thenAnswer(invocation -> {
            bothInFlight.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return priced("200");
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Quote> first = pool.submit(() -> service.quote(NAIROBI_LAT, NAIROBI_LNG, BASKET));
            Future<Quote> second = pool.submit(() -> service.quote(NAIROBI_LAT, NAIROBI_LNG, BASKET));
            assertThat(bothInFlight.await(10, TimeUnit.SECONDS)).as("two quotes are in flight").isTrue();

            assertThatThrownBy(() -> service.quote(NAIROBI_LAT, NAIROBI_LNG, BASKET))
                    .isInstanceOfSatisfying(TumaBodaException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo("BUSY");
                        assertThat(e.retryable()).as("the customer can simply try again").isTrue();
                    });

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).totalKes()).isEqualByComparingTo("200");
            assertThat(second.get(10, TimeUnit.SECONDS).totalKes()).isEqualByComparingTo("200");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(service.quote(NAIROBI_LAT, NAIROBI_LNG, BASKET).totalKes())
                .as("the slots were given back")
                .isEqualByComparingTo("200");
    }

    @Test
    void aFailedQuoteGivesItsSlotBack() {
        TumaBodaQuoteService service = new TumaBodaQuoteService(config, client, 1, Duration.ofMillis(50));
        when(client.quote(any(), any(), any(PackageSpec.class)))
                .thenThrow(new TumaBodaException("UPSTREAM", "TumaBoda is down", true));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.quote(NAIROBI_LAT, NAIROBI_LNG, BASKET))
                    .isInstanceOfSatisfying(TumaBodaException.class,
                            e -> assertThat(e.errorCode()).as("the client's own failure, never BUSY").isEqualTo("UPSTREAM"));
        }
    }

    @Test
    void anImplausiblePointNeverTakesASlot() {
        TumaBodaQuoteService service = new TumaBodaQuoteService(config, client, 1, Duration.ofMillis(50));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.quote(new BigDecimal("51.5"), new BigDecimal("-0.12"), BASKET))
                    .isInstanceOfSatisfying(TumaBodaException.class,
                            e -> assertThat(e.errorCode()).isEqualTo("BAD_LOCATION"));
        }
    }
}
