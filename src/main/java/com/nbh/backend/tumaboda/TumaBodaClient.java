package com.nbh.backend.tumaboda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.tumaboda.TumaBodaDtos.Delivery;
import com.nbh.backend.tumaboda.TumaBodaDtos.DeliveryRequest;
import com.nbh.backend.tumaboda.TumaBodaDtos.PackageSpec;
import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceDetails;
import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceSuggestion;
import com.nbh.backend.tumaboda.TumaBodaDtos.Quote;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * TumaBoda's partner API: quote a delivery, book one, read one back, and look up addresses. Authenticated
 * by a static partner API key in the {@code X-API-Key} header (no token exchange). Amounts arrive in cents
 * and are converted to KES here so nothing else deals in cents.
 *
 * <p><b>NOT LIVE-TESTED against TumaBoda.</b> There is no TumaBoda partner account for Trendysh yet. The
 * endpoints, request bodies and response fields are what Moments Packaging's working integration sends and
 * reads (its TumaBodaService), and the tests here run them against a fake HTTP server — which proves this
 * code does what it intends, not that TumaBoda agrees. Before trusting it: get a sandbox key, set
 * {@code app.tumaboda.api-key}, and do one real quote and one real booking in the sandbox.
 *
 * <p>Response parsing is deliberately tolerant about wrapping ({@code {"delivery": {...}}} versus the bare
 * object), since that is the part of an unverified API most likely to differ. Timeouts are explicit (a hung
 * TumaBoda must not tie up request threads). GETs retry once on a network error or 5xx; POSTs never retry
 * here — booking is retried by {@link TumaBodaBookingService}, safely, because the reference makes a repeat
 * booking come back as the same delivery. Every variable in a URL goes through URI-template expansion, which
 * encodes it exactly once (pre-encoding it would double-encode it: {@code %20} becoming {@code %2520}).
 */
@Service
public class TumaBodaClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final long RETRY_DELAY_MS = 500;
    private static final String CATEGORY = "other";
    private static final int MAX_SUGGESTIONS = 8;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TumaBodaConfig config;

    public TumaBodaClient(RestClient.Builder restClientBuilder, TumaBodaConfig config, ObjectMapper objectMapper) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        this.restClient = restClientBuilder.baseUrl(config.baseUrl()).requestFactory(requestFactory).build();
        this.config = config;
        this.objectMapper = objectMapper;
    }

    // ---- quoting and booking ----

    /** What delivering to this point would cost. */
    public Quote quote(BigDecimal lat, BigDecimal lng, PackageSpec spec) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dropoff", Map.of("latitude", lat, "longitude", lng));
        body.put("package", Map.of("size", spec.size().wire()));
        if (spec.van()) {
            body.put("vehicleType", "van");
        }
        JsonNode json = unwrap(send("/v1/partner/deliveries/quote", body), "totalCostCents", "quote", "data");
        // A missing or zero price is far likelier to be a field TumaBoda renamed than a free delivery, and
        // charging a customer KES 0 for a rider would be a silent loss — so it is an error, not a fee.
        BigDecimal total = json.hasNonNull("totalCostCents") ? centsToKes(json.get("totalCostCents")) : null;
        if (total == null || total.signum() <= 0) {
            throw new TumaBodaException("BAD_RESPONSE", "TumaBoda's quote had no usable price", false);
        }
        return new Quote(total, centsToKes(json.path("serviceFeeCents")),
                decimalOrNull(json.path("estimatedDistanceKm")),
                json.hasNonNull("estimatedDurationMinutes") ? json.get("estimatedDurationMinutes").asInt() : null);
    }

    /**
     * Books a rider. The request's {@code reference} makes this idempotent: if TumaBoda already has a
     * delivery under it (a booking whose answer we never saw), its 409 is turned into that delivery. Like
     * Moments, ANY 409 counts as "reference reused" — the error code is not inspected.
     */
    public Delivery create(DeliveryRequest request) {
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("name", request.recipient().name());
        recipient.put("phone", request.recipient().phone());
        recipient.put("location", request.recipient().location());
        // This endpoint takes the coordinates as strings (the quote takes numbers). toPlainString, because
        // a double's toString turns 0.0005 into "5.0E-4", and Kenya straddles the equator.
        recipient.put("latitude", request.recipient().latitude().toPlainString());
        recipient.put("longitude", request.recipient().longitude().toPlainString());

        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("description", request.description());
        pack.put("size", request.packageSpec().size().wire());
        pack.put("category", CATEGORY);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", recipient);
        body.put("package", pack);
        if (request.packageSpec().van()) {
            body.put("vehicleType", "van");
        }
        body.put("reference", request.reference());

        try {
            return parseDelivery(send("/v1/partner/deliveries", body));
        } catch (TumaBodaException e) {
            if (e.httpStatus() == 409) {
                return findByReference(request.reference()).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    public Delivery get(String deliveryId) {
        return parseDelivery(fetch("/v1/partner/deliveries/{id}", deliveryId));
    }

    public Optional<Delivery> findByReference(String reference) {
        try {
            return Optional.of(parseDelivery(fetch("/v1/partner/deliveries/by-reference?reference={reference}", reference)));
        } catch (TumaBodaException e) {
            if (e.httpStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // ---- addresses ----

    /** Address suggestions for what the customer has typed so far. */
    public List<PlaceSuggestion> autocomplete(String input) {
        JsonNode json = fetch("/v1/partner/maps/autocomplete?input={input}", input);
        List<PlaceSuggestion> suggestions = new ArrayList<>();
        for (JsonNode prediction : json.path("predictions")) {
            String placeId = text(prediction, "place_id");
            String description = text(prediction, "description");
            if (placeId != null && description != null) {
                suggestions.add(new PlaceSuggestion(placeId, description));
            }
            if (suggestions.size() == MAX_SUGGESTIONS) {
                break;
            }
        }
        return suggestions;
    }

    /** The coordinates (and TumaBoda's own wording) for a place chosen from {@link #autocomplete}; empty when it has none. */
    public Optional<PlaceDetails> placeDetails(String placeId) {
        JsonNode json = fetch("/v1/partner/maps/place-details?place_id={placeId}", placeId);
        JsonNode result = json.has("result") ? json.get("result") : json;
        JsonNode location = result.path("geometry").path("location");
        if (!location.hasNonNull("lat") || !location.hasNonNull("lng")) {
            return Optional.empty();
        }
        return Optional.of(new PlaceDetails(placeId, new BigDecimal(location.get("lat").asText()),
                new BigDecimal(location.get("lng").asText()), text(result, "formatted_address"), text(result, "name")));
    }

    /**
     * The address at a raw point — "use my location" reverse-geocoded to something readable. Empty when
     * TumaBoda found nothing there (ZERO_RESULTS): the caller falls back to the seeded area list or the
     * raw coordinates, never an exception, since a customer's own GPS reading is still perfectly usable
     * for pricing even with no label for it.
     */
    public Optional<PlaceDetails> reverseGeocode(BigDecimal lat, BigDecimal lng) {
        JsonNode json = fetch("/v1/partner/geocode?latlng={latlng}", lat.toPlainString() + "," + lng.toPlainString());
        JsonNode location = json.path("location");
        if (!location.hasNonNull("latitude") || !location.hasNonNull("longitude")) {
            return Optional.empty();
        }
        return Optional.of(new PlaceDetails(text(location, "placeId"),
                new BigDecimal(location.get("latitude").asText()), new BigDecimal(location.get("longitude").asText()),
                text(location, "formattedAddress"), null));
    }

    // ---- HTTP ----

    private JsonNode send(String path, Object body) {
        return call(() -> restClient.post().uri(path)
                .header("X-API-Key", config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
    }

    private JsonNode fetch(String template, Object... variables) {
        Supplier<String> request = () -> restClient.get().uri(template, variables)
                .header("X-API-Key", config.apiKey())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        try {
            return call(request);
        } catch (TumaBodaException e) {
            if (!e.retryable()) {
                throw e;
            }
            sleepQuietly();
            return call(request);
        }
    }

    private JsonNode call(Supplier<String> request) {
        if (!config.enabled()) {
            throw new TumaBodaException("NOT_CONFIGURED", "TumaBoda delivery isn't available right now", false);
        }
        try {
            String body = request.get();
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new TumaBodaException("NETWORK", "Couldn't reach TumaBoda: " + e.getMessage(), true, e);
        } catch (JsonProcessingException e) {
            throw new TumaBodaException("BAD_RESPONSE", "TumaBoda sent a reply that isn't valid JSON", true, e);
        }
    }

    /** TumaBoda's errors read {@code {"error": {"code": "...", "message": "..."}}}. */
    private TumaBodaException translate(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        String code = "HTTP_" + status.value();
        String message = "TumaBoda answered " + status.value();
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString()).path("error");
            if (error.hasNonNull("code")) {
                code = error.get("code").asText();
            }
            if (error.hasNonNull("message")) {
                message = error.get("message").asText();
            }
        } catch (Exception ignored) {
            // Not JSON: keep the status-based defaults.
        }
        boolean retryable = status.is5xxServerError() || status.value() == 429 || status.value() == 408;
        return new TumaBodaException(code, message, retryable, status.value(), e);
    }

    // ---- parsing ----

    private static JsonNode unwrap(JsonNode json, String mustContain, String... wrappers) {
        if (json.has(mustContain)) {
            return json;
        }
        for (String wrapper : wrappers) {
            if (json.path(wrapper).has(mustContain)) {
                return json.get(wrapper);
            }
        }
        return json;
    }

    private static Delivery parseDelivery(JsonNode response) {
        JsonNode d = unwrap(response, "id", "delivery", "data");
        if (!d.hasNonNull("id")) {
            throw new TumaBodaException("BAD_RESPONSE", "TumaBoda's reply had no delivery id", true);
        }
        return new Delivery(text(d, "id"), text(d, "deliveryNumber"), text(d, "status"), text(d, "trackingCode"),
                text(d, "trackingUrl"), text(d, "riderId"),
                d.hasNonNull("totalCostCents") ? centsToKes(d.get("totalCostCents")) : null);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static BigDecimal centsToKes(JsonNode cents) {
        return cents.isMissingNode() || cents.isNull() ? null : new BigDecimal(cents.asText()).movePointLeft(2);
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : new BigDecimal(node.asText());
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
