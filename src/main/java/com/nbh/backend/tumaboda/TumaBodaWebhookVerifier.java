package com.nbh.backend.tumaboda;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Checks that a webhook really came from TumaBoda. Its {@code X-Tuma-Signature} header reads
 * {@code t=<unix seconds>,v1=<hex>}, where {@code v1} is HMAC-SHA256, keyed with the webhook secret, over
 * {@code "<t>.<raw body>"} — the exact bytes received, so the body must not be parsed and re-serialised
 * first. A timestamp more than five minutes off is refused, so a captured request cannot be replayed later.
 * With no secret configured every webhook is refused: an unauthenticated endpoint that can mark orders
 * delivered is worse than one that is off (same stance as the M-PESA callback secret).
 */
@Component
public class TumaBodaWebhookVerifier {

    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final TumaBodaConfig config;
    private final Clock clock;

    public TumaBodaWebhookVerifier(TumaBodaConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** False while no webhook secret is set: every webhook is then refused, so there is no point reading one. */
    public boolean configured() {
        return !config.webhookSecret().isBlank();
    }

    public boolean verify(String header, byte[] body) {
        String secret = config.webhookSecret();
        if (secret.isBlank() || header == null || header.isBlank() || body == null) {
            return false;
        }
        String timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("t")) {
                timestamp = pair[1];
            } else if (pair.length == 2 && pair[0].equals("v1")) {
                signature = pair[1];
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(seconds), Instant.now(clock)).abs().compareTo(TOLERANCE) > 0) {
            return false;
        }
        byte[] expected = hmac(secret, timestamp, body);
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(signature.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, supplied);
    }

    /** Exposed so tests (and a future sender) build signatures exactly the way this class checks them. */
    static byte[] hmac(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }
}
