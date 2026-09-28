package com.nbh.backend.tumaboda;

import com.nbh.backend.tumaboda.TumaBodaDtos.PackageSize;
import com.nbh.backend.tumaboda.TumaBodaDtos.PackageSpec;
import com.nbh.backend.tumaboda.TumaBodaDtos.Quote;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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

    private final TumaBodaConfig config;
    private final TumaBodaClient client;

    public TumaBodaQuoteService(TumaBodaConfig config, TumaBodaClient client) {
        this.config = config;
        this.client = client;
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

    /** @throws TumaBodaException when the integration is off, the point is implausible, or TumaBoda cannot serve it. */
    public Quote quote(BigDecimal lat, BigDecimal lng, BigDecimal productValueKes) {
        if (!enabled()) {
            throw new TumaBodaException("NOT_CONFIGURED", "TumaBoda delivery isn't available right now", false);
        }
        if (!isPlausibleKenyanPoint(lat, lng)) {
            throw new TumaBodaException("BAD_LOCATION", "That location doesn't look like an address in Kenya", false);
        }
        return client.quote(lat, lng, packageFor(productValueKes));
    }
}
