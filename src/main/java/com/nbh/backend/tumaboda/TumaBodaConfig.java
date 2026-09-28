package com.nbh.backend.tumaboda;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * TumaBoda partner-API settings, all from the environment. Blank {@code api-key} means the integration
 * is OFF: checkout offers TumaBoda as a phone-arranged delivery exactly as before, and nothing here
 * calls out. Turning it on needs a TumaBoda partner account for Trendysh (an API key, a webhook secret,
 * and the shop's pickup location registered on TumaBoda's side, since the API takes no pickup address).
 *
 * <p>Point {@code base-url} at TumaBoda's sandbox or production: {@code https://sandboxapi.tumaboda.co.ke/api}
 * (the default, so a fresh setup can never book a real rider) or {@code https://api.tumaboda.co.ke/api}.
 */
@Component
public class TumaBodaConfig {

    private final String apiKey;
    private final String baseUrl;
    private final String webhookSecret;
    private final BigDecimal largePackageThresholdKes;
    private final BigDecimal vanThresholdKes;

    public TumaBodaConfig(
            @Value("${app.tumaboda.api-key:}") String apiKey,
            @Value("${app.tumaboda.base-url:https://sandboxapi.tumaboda.co.ke/api}") String baseUrl,
            @Value("${app.tumaboda.webhook-secret:}") String webhookSecret,
            @Value("${app.tumaboda.large-package-threshold-kes:5000}") BigDecimal largePackageThresholdKes,
            @Value("${app.tumaboda.van-threshold-kes:20000}") BigDecimal vanThresholdKes) {
        // A key pasted from a portal often carries a trailing newline or space that would make every call 401.
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.largePackageThresholdKes = largePackageThresholdKes;
        this.vanThresholdKes = vanThresholdKes;
    }

    public boolean enabled() {
        return !apiKey.isBlank();
    }

    public String apiKey() { return apiKey; }

    public String baseUrl() { return baseUrl; }

    /** Blank means every webhook is rejected — same fail-closed stance as the M-PESA callback secret. */
    public String webhookSecret() { return webhookSecret; }

    /**
     * Package size and vehicle are a placeholder heuristic on the order's product value (copied from how
     * Moments does it): the catalogue holds no weights or dimensions. A bulky, cheap item would be sent as
     * "small"; revisit once products carry a shipping size.
     */
    public BigDecimal largePackageThresholdKes() { return largePackageThresholdKes; }

    public BigDecimal vanThresholdKes() { return vanThresholdKes; }
}
