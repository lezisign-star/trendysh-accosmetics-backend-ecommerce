package com.nbh.backend.tumaboda;

import com.nbh.backend.tumaboda.TumaBodaDtos.PackageSize;
import com.nbh.backend.tumaboda.TumaBodaDtos.PackageSpec;
import com.nbh.backend.tumaboda.TumaBodaDtos.Quote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * What a TumaBoda delivery would cost, for the checkout preview and again (server-side, so a customer
 * cannot pick their own fee) when the order is placed. Off when the integration has no API key: callers
 * then fall back to a phone-arranged delivery.
 */
@Service
public class TumaBodaQuoteService {

    // Kenya's rough bounding box: rejects a swapped lat/lng pair or a pin dropped abroad before spending a quote call on it.
    private static final BigDecimal MIN_LAT = new BigDecimal("-4.9");
    private static final BigDecimal MAX_LAT = new BigDecimal("5.2");
    private static final BigDecimal MIN_LNG = new BigDecimal("33.8");
    private static final BigDecimal MAX_LNG = new BigDecimal("42.0");

    /**
     * At most this many quote calls to TumaBoda run at once. Checkout quotes inside the request's
     * transaction, so every call in flight also holds a database connection while it waits on the network:
     * if TumaBoda slows down, an unbounded number of shoppers could take the whole pool with them and
     * stall the shop, not just delivery pricing.
     */
    static final int MAX_CONCURRENT_QUOTES = 4;
    static final Duration MAX_WAIT_FOR_A_SLOT = Duration.ofSeconds(2);

    private final TumaBodaConfig config;
    private final TumaBodaClient client;
    private final Semaphore slots;
    private final Duration maxWait;

    @Autowired
    public TumaBodaQuoteService(TumaBodaConfig config, TumaBodaClient client) {
        this(config, client, MAX_CONCURRENT_QUOTES, MAX_WAIT_FOR_A_SLOT);
    }

    TumaBodaQuoteService(TumaBodaConfig config, TumaBodaClient client, int maxConcurrent, Duration maxWait) {
        this.config = config;
        this.client = client;
        this.slots = new Semaphore(maxConcurrent, true);
        this.maxWait = maxWait;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** Size and vehicle from the order's product value — see {@link TumaBodaConfig#largePackageThresholdKes()} for why that is only a heuristic. */
    public PackageSpec packageFor(BigDecimal productValueKes) {
        boolean large = productValueKes.compareTo(config.largePackageThresholdKes()) >= 0;
        boolean van = productValueKes.compareTo(config.vanThresholdKes()) >= 0;
        return new PackageSpec(large ? PackageSize.LARGE : PackageSize.SMALL, van);
    }

    public static boolean isPlausibleKenyanPoint(BigDecimal lat, BigDecimal lng) {
        return lat != null && lng != null
                && lat.compareTo(MIN_LAT) >= 0 && lat.compareTo(MAX_LAT) <= 0
                && lng.compareTo(MIN_LNG) >= 0 && lng.compareTo(MAX_LNG) <= 0;
    }

    /**
     * The price is rounded UP to a whole shilling: M-PESA takes whole shillings, so a fee with cents could
     * not be paid exactly, and rounding up (never down) keeps the customer's payment covering what
     * TumaBoda charges.
     *
     * @throws TumaBodaException when the integration is off, the point is implausible, TumaBoda cannot
     *                           serve it, or (BUSY, retryable) too many quotes are already waiting on TumaBoda.
     */
    public Quote quote(BigDecimal lat, BigDecimal lng, BigDecimal productValueKes) {
        if (!enabled()) {
            throw new TumaBodaException("NOT_CONFIGURED", "TumaBoda delivery isn't available right now", false);
        }
        if (!isPlausibleKenyanPoint(lat, lng)) {
            throw new TumaBodaException("BAD_LOCATION", "That location doesn't look like an address in Kenya", false);
        }
        acquireSlot();
        try {
            Quote quoted = client.quote(lat, lng, packageFor(productValueKes));
            return new Quote(quoted.totalKes().setScale(0, RoundingMode.CEILING), quoted.serviceFeeKes(),
                    quoted.distanceKm(), quoted.durationMinutes());
        } finally {
            slots.release();
        }
    }

    private void acquireSlot() {
        try {
            if (!slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TumaBodaException("BUSY", "Too many TumaBoda price checks are running right now", true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TumaBodaException("BUSY", "Interrupted while waiting to price a TumaBoda delivery", true);
        }
    }
}
