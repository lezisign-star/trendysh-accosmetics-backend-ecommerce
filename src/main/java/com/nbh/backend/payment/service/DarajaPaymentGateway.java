package com.nbh.backend.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M-Pesa STK push via Safaricom's Daraja API — the founder's confirmed
 * payment provider (session 5), same integration pattern as Moments
 * Packaging's DarajaService (OAuth client-credentials token, then a
 * password-authenticated STK push request).
 *
 * <b>NOT LIVE-TESTED — this needs real credentials this session does not
 * have.</b> {@code app.mpesa.*} properties are unset placeholders in
 * application.yml. The request/response shapes below follow Safaricom's
 * documented Daraja v1 API exactly, but calling {@link #initiateStkPush}
 * with placeholder credentials will fail (most likely a 400/401 from
 * Safaricom's sandbox) — that failure is expected and proves nothing about
 * whether this code is correct. Before this can be trusted: get real sandbox
 * consumer key/secret/shortcode/passkey from Safaricom's developer portal,
 * set them via the env vars below, and do one real STK push against the
 * sandbox with a real test phone number.
 *
 * <p><b>{@code app.mpesa.callback-url} must end with the exact value of
 * {@code app.mpesa.callback-secret}</b> — see {@link com.nbh.backend.payment.controller.PaymentController#mpesaCallback}
 * (§4 C1). Registering a callback URL with Safaricom that doesn't carry the
 * matching secret segment means every real callback gets rejected as 404.
 *
 * <p>§4 C8 fix: a single {@link RestClient} (built once, with explicit
 * connect/read timeouts) is reused for every call instead of a bare {@code
 * RestClient.create(baseUrl)} per method call — the latter had no timeout at
 * all, which on the default Tomcat thread pool meant a single hung Safaricom
 * endpoint could exhaust every request thread in the application, taking
 * down staff login and stock receiving along with payments. The OAuth token
 * is also cached for its Daraja-documented ~1h validity instead of being
 * re-fetched on every STK push, which halved both the latency and the
 * failure surface of every payment initiation.
 */
@Service
public class DarajaPaymentGateway {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /** Daraja documents ~3600s token validity; refresh a safety margin early rather than racing expiry mid-request. */
    private static final Duration TOKEN_EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(60);
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofSeconds(3500);

    @Value("${app.mpesa.consumer-key:}")
    private String consumerKey;

    @Value("${app.mpesa.consumer-secret:}")
    private String consumerSecret;

    @Value("${app.mpesa.shortcode:}")
    private String shortcode;

    @Value("${app.mpesa.passkey:}")
    private String passkey;

    @Value("${app.mpesa.callback-url:}")
    private String callbackUrl;

    private final RestClient restClient;
    private final Clock clock;
    // §4 C9: the app-wide bean, not a bare `new ObjectMapper()` — a Daraja
    // response containing an Instant would otherwise serialise differently
    // from every other date in the application.
    private final ObjectMapper objectMapper;

    private volatile CachedToken cachedToken;

    public DarajaPaymentGateway(RestClient.Builder restClientBuilder,
                                 @Value("${app.mpesa.base-url:https://sandbox.safaricom.co.ke}") String baseUrl,
                                 Clock clock,
                                 ObjectMapper objectMapper) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * @param phoneNumber MSISDN in 2547XXXXXXXX format (Daraja rejects any other shape)
     * @param amount whole-shilling amount (Daraja's sandbox does not accept decimals)
     * @param accountReference shown to the customer on their phone — the order number
     * @return Safaricom's CheckoutRequestID, used to correlate the eventual callback
     */
    public String initiateStkPush(String phoneNumber, long amount, String accountReference, String transactionDesc) {
        if (consumerKey.isBlank() || consumerSecret.isBlank() || shortcode.isBlank() || passkey.isBlank()) {
            throw new IllegalStateException(
                    "M-Pesa credentials are not configured (app.mpesa.consumer-key/consumer-secret/shortcode/passkey) "
                            + "— this is expected until real Safaricom Daraja credentials are obtained; see this "
                            + "class's javadoc.");
        }

        String accessToken = fetchAccessToken();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String password = Base64.getEncoder().encodeToString(
                (shortcode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", shortcode);
        body.put("Password", password);
        body.put("Timestamp", timestamp);
        body.put("TransactionType", "CustomerPayBillOnline");
        body.put("Amount", amount);
        body.put("PartyA", phoneNumber);
        body.put("PartyB", shortcode);
        body.put("PhoneNumber", phoneNumber);
        body.put("CallBackURL", callbackUrl);
        body.put("AccountReference", accountReference);
        body.put("TransactionDesc", transactionDesc);

        String response = restClient.post()
                .uri("/mpesa/stkpush/v1/processrequest")
                .headers(h -> h.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode json = objectMapper.readTree(response);
            return json.path("CheckoutRequestID").asText(null);
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected Daraja STK push response: " + response, e);
        }
    }

    /** §4 C8: cached for its documented ~1h validity — re-fetched only once expired (with a safety margin). */
    private synchronized String fetchAccessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.expiresAt().isAfter(Instant.now(clock))) {
            return current.token();
        }

        String credentials = Base64.getEncoder().encodeToString(
                (consumerKey + ":" + consumerSecret).getBytes(StandardCharsets.UTF_8));

        String response = restClient.get()
                .uri("/oauth/v1/generate?grant_type=client_credentials")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .retrieve()
                .body(String.class);

        try {
            JsonNode json = objectMapper.readTree(response);
            String token = json.path("access_token").asText(null);
            if (token == null) {
                throw new IllegalStateException("Daraja OAuth response had no access_token: " + response);
            }
            Duration ttl = json.hasNonNull("expires_in")
                    ? Duration.ofSeconds(json.path("expires_in").asLong(DEFAULT_TOKEN_TTL.toSeconds()))
                    : DEFAULT_TOKEN_TTL;
            Instant expiresAt = Instant.now(clock).plus(ttl).minus(TOKEN_EXPIRY_SAFETY_MARGIN);
            cachedToken = new CachedToken(token, expiresAt);
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Daraja OAuth response: " + response, e);
        }
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
