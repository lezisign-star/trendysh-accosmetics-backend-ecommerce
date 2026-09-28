package com.nbh.backend.tumaboda;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature scheme is Moments' working one: {@code X-Tuma-Signature: t=<unix seconds>,v1=<hex>} where
 * v1 is HMAC-SHA256 over "t.rawBody" keyed with the webhook secret's UTF-8 bytes, and a timestamp more
 * than five minutes away is refused.
 */
class TumaBodaWebhookVerifierTest {

    private static final String SECRET = "whsec_unit_test_secret";
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final byte[] body = "{\"id\":\"evt_1\",\"event\":\"delivery.status_updated\",\"data\":{\"id\":\"d-1\",\"status\":\"in_transit\"}}"
            .getBytes(StandardCharsets.UTF_8);
    private final TumaBodaWebhookVerifier verifier = new TumaBodaWebhookVerifier(config(SECRET), Clock.fixed(NOW, ZoneOffset.UTC));

    private static TumaBodaConfig config(String secret) {
        return new TumaBodaConfig("key", "http://localhost/api", secret, new BigDecimal("5000"), new BigDecimal("20000"));
    }

    /** Signs the way the test's own reading of the scheme says, independently of the class under test. */
    private static String hex(String secret, long timestamp, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        mac.update(payload);
        return HexFormat.of().formatHex(mac.doFinal());
    }

    private String header(long timestamp) throws Exception {
        return "t=" + timestamp + ",v1=" + hex(SECRET, timestamp, body);
    }

    @Test
    void aGoodSignatureIsAccepted() throws Exception {
        assertThat(verifier.verify(header(NOW.getEpochSecond()), body)).isTrue();
    }

    @Test
    void theClassAndTheIndependentComputationAgree() throws Exception {
        long t = NOW.getEpochSecond();
        assertThat(HexFormat.of().formatHex(TumaBodaWebhookVerifier.hmac(SECRET, String.valueOf(t), body)))
                .isEqualTo(hex(SECRET, t, body));
    }

    @Test
    void anUppercaseHexSignatureIsTheSameSignature() throws Exception {
        long t = NOW.getEpochSecond();
        assertThat(verifier.verify("t=" + t + ",v1=" + hex(SECRET, t, body).toUpperCase(), body)).isTrue();
    }

    @Test
    void aBodyChangedByEvenOneByteIsRefused() throws Exception {
        String signed = header(NOW.getEpochSecond());
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("in_transit", "delivered").getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(signed, tampered)).isFalse();
    }

    @Test
    void aSignatureMadeWithAnotherSecretIsRefused() throws Exception {
        long t = NOW.getEpochSecond();

        assertThat(verifier.verify("t=" + t + ",v1=" + hex("some-other-secret", t, body), body)).isFalse();
    }

    @Test
    void aTimestampSwappedAfterSigningIsRefused() throws Exception {
        long t = NOW.getEpochSecond();
        String signature = hex(SECRET, t, body);

        assertThat(verifier.verify("t=" + (t - 1) + ",v1=" + signature, body)).isFalse();
    }

    @Test
    void theFiveMinuteWindowIsEnforcedInBothDirections() throws Exception {
        assertThat(verifier.verify(header(NOW.minusSeconds(299).getEpochSecond()), body)).isTrue();
        assertThat(verifier.verify(header(NOW.plusSeconds(299).getEpochSecond()), body)).isTrue();
        assertThat(verifier.verify(header(NOW.minusSeconds(301).getEpochSecond()), body)).as("a replay").isFalse();
        assertThat(verifier.verify(header(NOW.plusSeconds(301).getEpochSecond()), body)).as("from the future").isFalse();
    }

    @Test
    void missingOrMalformedHeadersAreRefused() throws Exception {
        long t = NOW.getEpochSecond();
        String good = hex(SECRET, t, body);

        assertThat(verifier.verify(null, body)).isFalse();
        assertThat(verifier.verify("", body)).isFalse();
        assertThat(verifier.verify("t=" + t, body)).isFalse();
        assertThat(verifier.verify("v1=" + good, body)).isFalse();
        assertThat(verifier.verify("t=soon,v1=" + good, body)).isFalse();
        assertThat(verifier.verify("t=" + t + ",v1=not-hex", body)).isFalse();
        assertThat(verifier.verify("t=" + t + ",v1=", body)).isFalse();
        assertThat(verifier.verify(header(t), null)).isFalse();
    }

    @Test
    void withNoSecretConfiguredEverythingIsRefusedEvenAWellFormedSignature() throws Exception {
        long t = NOW.getEpochSecond();
        // HMAC cannot even be keyed with an empty secret, so a well-formed 64-digit signature stands in
        // for one an attacker might compute for a shop that never set its secret.
        String wellFormed = "t=" + t + ",v1=" + "ab".repeat(32);

        for (String secret : new String[]{"", "   "}) {
            TumaBodaWebhookVerifier unconfigured = new TumaBodaWebhookVerifier(config(secret), Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(unconfigured.verify(wellFormed, body)).as("secret '%s'", secret).isFalse();
            assertThat(unconfigured.verify(header(t), body)).isFalse();
        }
    }

    @Test
    void theSecretIsUsedAsItsUtf8BytesNotDecoded() throws Exception {
        // A secret that looks like base64 is still just its own characters.
        String looksEncoded = "d2hzZWNfdGVzdA==";
        TumaBodaWebhookVerifier v = new TumaBodaWebhookVerifier(config(looksEncoded), Clock.fixed(NOW, ZoneOffset.UTC));
        long t = NOW.getEpochSecond();

        assertThat(v.verify("t=" + t + ",v1=" + hex(looksEncoded, t, body), body)).isTrue();
        assertThat(v.verify("t=" + t + ",v1=" + hex("whsec_test", t, body), body)).isFalse();
    }
}
