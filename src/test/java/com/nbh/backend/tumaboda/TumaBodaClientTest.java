package com.nbh.backend.tumaboda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.tumaboda.TumaBodaDtos.Delivery;
import com.nbh.backend.tumaboda.TumaBodaDtos.DeliveryRequest;
import com.nbh.backend.tumaboda.TumaBodaDtos.PackageSize;
import com.nbh.backend.tumaboda.TumaBodaDtos.PackageSpec;
import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceDetails;
import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceSuggestion;
import com.nbh.backend.tumaboda.TumaBodaDtos.Quote;
import com.nbh.backend.tumaboda.TumaBodaDtos.Recipient;
import com.nbh.backend.tumaboda.FakeTumaBodaServer.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real HTTP client against {@link FakeTumaBodaServer}: what it sends (paths, key header, body shapes,
 * number-versus-string coordinates) and how it reads and classifies what comes back. The shapes come from
 * Moments' working integration; TumaBoda itself has not been called.
 */
class TumaBodaClientTest {

    private static FakeTumaBodaServer server;
    private final ObjectMapper json = new ObjectMapper();
    private TumaBodaClient client;

    @BeforeAll
    static void startServer() {
        server = FakeTumaBodaServer.startUnchecked();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void setUp() {
        server.reset();
        client = clientFor("test-api-key", server.baseUrl());
    }

    private TumaBodaClient clientFor(String apiKey, String baseUrl) {
        return new TumaBodaClient(RestClient.builder(),
                new TumaBodaConfig(apiKey, baseUrl, "whsec", new BigDecimal("5000"), new BigDecimal("20000")), json);
    }

    private JsonNode bodyOf(FakeTumaBodaServer.Recorded request) throws Exception {
        return json.readTree(request.body());
    }

    private static final String DELIVERY = """
            {"delivery":{"id":"d-1","transactionId":"t-1","deliveryNumber":"TB-100","status":"pending",
             "trackingCode":"TRK1","trackingUrl":"https://track.example/TRK1","riderId":null,"totalCostCents":45000}}
            """;

    private DeliveryRequest request(String lat, String lng) {
        return new DeliveryRequest(
                new Recipient("Wanjiku Mwangi", "+254711222333", "Kilimani, Nairobi", new BigDecimal(lat), new BigDecimal(lng)),
                "Order NBH-1", new PackageSpec(PackageSize.SMALL, false), "NBH-1");
    }

    // ---- quote ----

    @Test
    void quoteSendsTheDropoffAsNumbersAndReadsCentsAsShillings() throws Exception {
        server.route("POST", "/v1/partner/deliveries/quote", Reply.ok(
                "{\"totalCostCents\":45000,\"serviceFeeCents\":5000,\"estimatedDistanceKm\":6.4,\"estimatedDurationMinutes\":22}"));

        Quote quote = client.quote(new BigDecimal("-1.2921"), new BigDecimal("36.8219"), new PackageSpec(PackageSize.SMALL, false));

        assertThat(quote.totalKes()).isEqualByComparingTo("450");
        assertThat(quote.serviceFeeKes()).isEqualByComparingTo("50");
        assertThat(quote.distanceKm()).isEqualByComparingTo("6.4");
        assertThat(quote.durationMinutes()).isEqualTo(22);

        FakeTumaBodaServer.Recorded sent = server.requestsTo("POST", "/v1/partner/deliveries/quote").get(0);
        assertThat(sent.apiKey()).isEqualTo("test-api-key");
        JsonNode body = bodyOf(sent);
        assertThat(body.at("/dropoff/latitude").isNumber()).isTrue();
        assertThat(body.at("/dropoff/latitude").decimalValue()).isEqualByComparingTo("-1.2921");
        assertThat(body.at("/dropoff/longitude").decimalValue()).isEqualByComparingTo("36.8219");
        assertThat(body.at("/package/size").asText()).isEqualTo("small");
        assertThat(body.has("vehicleType")).isFalse();
    }

    @Test
    void aLargeVanOrderAsksForALargePackageAndAVan() throws Exception {
        server.route("POST", "/v1/partner/deliveries/quote", Reply.ok("{\"totalCostCents\":90000}"));

        client.quote(new BigDecimal("-1.29"), new BigDecimal("36.82"), new PackageSpec(PackageSize.LARGE, true));

        JsonNode body = bodyOf(server.requestsTo("POST", "/v1/partner/deliveries/quote").get(0));
        assertThat(body.at("/package/size").asText()).isEqualTo("large");
        assertThat(body.get("vehicleType").asText()).isEqualTo("van");
    }

    @Test
    void aQuoteWrappedInAnEnvelopeIsStillRead() {
        server.route("POST", "/v1/partner/deliveries/quote", Reply.ok("{\"quote\":{\"totalCostCents\":12345}}"));

        Quote quote = client.quote(new BigDecimal("-1.29"), new BigDecimal("36.82"), new PackageSpec(PackageSize.SMALL, false));

        assertThat(quote.totalKes()).isEqualByComparingTo("123.45");
        assertThat(quote.serviceFeeKes()).isNull();
    }

    @Test
    void aQuoteWithNoUsablePriceIsAnErrorNeverAFreeDelivery() {
        for (String body : List.of("{}", "{\"totalCostCents\":0}", "{\"totalCostCents\":null}")) {
            server.reset();
            server.route("POST", "/v1/partner/deliveries/quote", Reply.ok(body));

            assertThatThrownBy(() -> client.quote(new BigDecimal("-1.29"), new BigDecimal("36.82"),
                    new PackageSpec(PackageSize.SMALL, false)))
                    .as(body)
                    .isInstanceOfSatisfying(TumaBodaException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo("BAD_RESPONSE");
                        assertThat(e.retryable()).isFalse();
                    });
        }
    }

    // ---- create ----

    @Test
    void createSendsTheRecipientPackageAndReferenceAndReadsTheDelivery() throws Exception {
        server.route("POST", "/v1/partner/deliveries", Reply.ok(DELIVERY));

        Delivery delivery = client.create(request("-1.2921", "36.8219"));

        assertThat(delivery.id()).isEqualTo("d-1");
        assertThat(delivery.deliveryNumber()).isEqualTo("TB-100");
        assertThat(delivery.status()).isEqualTo("pending");
        assertThat(delivery.trackingCode()).isEqualTo("TRK1");
        assertThat(delivery.trackingUrl()).isEqualTo("https://track.example/TRK1");
        assertThat(delivery.riderId()).isNull();
        assertThat(delivery.totalKes()).isEqualByComparingTo("450");

        FakeTumaBodaServer.Recorded sent = server.requestsTo("POST", "/v1/partner/deliveries").get(0);
        assertThat(sent.apiKey()).isEqualTo("test-api-key");
        JsonNode body = bodyOf(sent);
        assertThat(body.at("/recipient/name").asText()).isEqualTo("Wanjiku Mwangi");
        assertThat(body.at("/recipient/phone").asText()).isEqualTo("+254711222333");
        assertThat(body.at("/recipient/location").asText()).isEqualTo("Kilimani, Nairobi");
        // This endpoint takes the coordinates as strings, unlike the quote's numbers.
        assertThat(body.at("/recipient/latitude").isTextual()).isTrue();
        assertThat(body.at("/recipient/latitude").asText()).isEqualTo("-1.2921");
        assertThat(body.at("/recipient/longitude").asText()).isEqualTo("36.8219");
        assertThat(body.at("/package/description").asText()).isEqualTo("Order NBH-1");
        assertThat(body.at("/package/size").asText()).isEqualTo("small");
        assertThat(body.at("/package/category").asText()).isEqualTo("other");
        assertThat(body.get("reference").asText()).isEqualTo("NBH-1");
    }

    @Test
    void aLatitudeNearTheEquatorIsNeverWrittenInScientificNotation() throws Exception {
        server.route("POST", "/v1/partner/deliveries", Reply.ok(DELIVERY));

        client.create(request("0.0005", "37.0001"));

        JsonNode body = bodyOf(server.requestsTo("POST", "/v1/partner/deliveries").get(0));
        assertThat(body.at("/recipient/latitude").asText()).isEqualTo("0.0005");
    }

    @Test
    void aDeliveryReplyWithNoIdIsRefused() {
        server.route("POST", "/v1/partner/deliveries", Reply.ok("{\"delivery\":{\"status\":\"pending\"}}"));

        assertThatThrownBy(() -> client.create(request("-1.29", "36.82")))
                .isInstanceOfSatisfying(TumaBodaException.class, e -> assertThat(e.errorCode()).isEqualTo("BAD_RESPONSE"));
    }

    @Test
    void a409OnCreateBecomesTheDeliveryAlreadyBookedUnderThatReference() {
        // Whatever error code TumaBoda puts on a 409, Moments treats it as "reference reused".
        server.route("POST", "/v1/partner/deliveries", Reply.error(409, "DUPLICATE_REFERENCE", "reference already used"));
        server.route("GET", "/v1/partner/deliveries/by-reference", Reply.ok(DELIVERY.replace("d-1", "d-existing")));

        Delivery delivery = client.create(request("-1.29", "36.82"));

        assertThat(delivery.id()).isEqualTo("d-existing");
        assertThat(server.requestsTo("GET", "/v1/partner/deliveries/by-reference").get(0).rawQuery())
                .isEqualTo("reference=NBH-1");
    }

    @Test
    void a409WithNothingUnderThatReferenceIsAnErrorNotAGuess() {
        server.route("POST", "/v1/partner/deliveries", Reply.error(409, "CONFLICT", "reference already used"));

        assertThatThrownBy(() -> client.create(request("-1.29", "36.82")))
                .isInstanceOfSatisfying(TumaBodaException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.errorCode()).isEqualTo("CONFLICT");
                });
    }

    // ---- reading a delivery back ----

    @Test
    void getReadsADeliveryByItsId() {
        server.route("GET", "/v1/partner/deliveries/d-1", Reply.ok(DELIVERY.replace("pending", "in_transit")));

        Delivery delivery = client.get("d-1");

        assertThat(delivery.status()).isEqualTo("in_transit");
    }

    @Test
    void findByReferenceEncodesTheReferenceOnceAndReportsNoMatchAsEmpty() {
        assertThat(client.findByReference("NBH 1&2")).isEmpty();

        String query = server.requestsTo("GET", "/v1/partner/deliveries/by-reference").get(0).rawQuery();
        assertThat(query).isEqualTo("reference=NBH%201%262");
    }

    // ---- failures ----

    @Test
    void errorsCarryTheApisCodeTheStatusAndWhetherToRetry() {
        assertFailure(400, "INVALID_ADDRESS", false);
        assertFailure(401, "UNAUTHORIZED", false);
        assertFailure(422, "UNSERVICEABLE", false);
        assertFailure(429, "RATE_LIMITED", true);
        assertFailure(503, "UNAVAILABLE", true);
    }

    private void assertFailure(int status, String code, boolean retryable) {
        server.reset();
        server.route("POST", "/v1/partner/deliveries", Reply.error(status, code, "message for " + code));

        assertThatThrownBy(() -> client.create(request("-1.29", "36.82")))
                .isInstanceOfSatisfying(TumaBodaException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(code);
                    assertThat(e.httpStatus()).isEqualTo(status);
                    assertThat(e.retryable()).as("retryable for %d", status).isEqualTo(retryable);
                    assertThat(e.getMessage()).isEqualTo("message for " + code);
                });
    }

    @Test
    void aNonJsonErrorPageStillBecomesATypedError() {
        server.route("POST", "/v1/partner/deliveries", new Reply(502, "<html>Bad gateway</html>"));

        assertThatThrownBy(() -> client.create(request("-1.29", "36.82")))
                .isInstanceOfSatisfying(TumaBodaException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("HTTP_502");
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void aGetIsRetriedOnceOnAServerError() {
        server.enqueue("GET", "/v1/partner/deliveries/d-1", Reply.error(500, "BOOM", "boom"));
        server.route("GET", "/v1/partner/deliveries/d-1", Reply.ok(DELIVERY));

        assertThat(client.get("d-1").id()).isEqualTo("d-1");
        assertThat(server.requestsTo("GET", "/v1/partner/deliveries/d-1")).hasSize(2);
    }

    @Test
    void aGetGivesUpAfterTheOneRetry() {
        server.route("GET", "/v1/partner/deliveries/d-1", Reply.error(500, "BOOM", "boom"));

        assertThatThrownBy(() -> client.get("d-1")).isInstanceOf(TumaBodaException.class);
        assertThat(server.requestsTo("GET", "/v1/partner/deliveries/d-1")).hasSize(2);
    }

    @Test
    void aClientErrorOnAGetIsNotRetried() {
        server.route("GET", "/v1/partner/deliveries/d-1", Reply.error(404, "NOT_FOUND", "no such delivery"));

        assertThatThrownBy(() -> client.get("d-1")).isInstanceOf(TumaBodaException.class);
        assertThat(server.requestsTo("GET", "/v1/partner/deliveries/d-1")).hasSize(1);
    }

    @Test
    void aPostIsNeverRetriedByTheClient() {
        server.route("POST", "/v1/partner/deliveries", Reply.error(500, "BOOM", "boom"));

        assertThatThrownBy(() -> client.create(request("-1.29", "36.82"))).isInstanceOf(TumaBodaException.class);
        assertThat(server.requestsTo("POST", "/v1/partner/deliveries")).hasSize(1);
    }

    @Test
    void anUnreachableTumaBodaIsARetryableNetworkFailure() {
        TumaBodaClient nobodyHome = clientFor("test-api-key", "http://127.0.0.1:1/api");

        assertThatThrownBy(() -> nobodyHome.quote(new BigDecimal("-1.29"), new BigDecimal("36.82"),
                new PackageSpec(PackageSize.SMALL, false)))
                .isInstanceOfSatisfying(TumaBodaException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("NETWORK");
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void withoutAnApiKeyNothingIsSent() {
        TumaBodaClient unconfigured = clientFor("  ", server.baseUrl());

        assertThatThrownBy(() -> unconfigured.quote(new BigDecimal("-1.29"), new BigDecimal("36.82"),
                new PackageSpec(PackageSize.SMALL, false)))
                .isInstanceOfSatisfying(TumaBodaException.class, e -> assertThat(e.errorCode()).isEqualTo("NOT_CONFIGURED"));
        assertThat(server.requests()).isEmpty();
    }

    // ---- addresses ----

    @Test
    void autocompleteReadsPredictionsAndEncodesTheSearchOnce() {
        server.route("GET", "/v1/partner/maps/autocomplete", Reply.ok("""
                {"predictions":[
                  {"place_id":"p1","description":"Kilimani, Nairobi, Kenya","types":["locality"]},
                  {"place_id":"p2","description":"Kileleshwa, Nairobi, Kenya"},
                  {"description":"no id, dropped"}]}
                """));

        List<PlaceSuggestion> suggestions = client.autocomplete("Kili nairobi");

        assertThat(suggestions).containsExactly(
                new PlaceSuggestion("p1", "Kilimani, Nairobi, Kenya"),
                new PlaceSuggestion("p2", "Kileleshwa, Nairobi, Kenya"));
        // Encoding the space twice would arrive as %2520 and TumaBoda would search for the literal text "%20".
        assertThat(server.requestsTo("GET", "/v1/partner/maps/autocomplete").get(0).rawQuery())
                .isEqualTo("input=Kili%20nairobi");
    }

    @Test
    void aSearchWithAnAmpersandCannotInjectAnotherParameter() {
        server.route("GET", "/v1/partner/maps/autocomplete", Reply.ok("{\"predictions\":[]}"));

        client.autocomplete("A&B place_id=x");

        String query = server.requestsTo("GET", "/v1/partner/maps/autocomplete").get(0).rawQuery();
        assertThat(query).startsWith("input=A%26B").doesNotContain("&");
    }

    @Test
    void placeDetailsReadsTheCoordinates() {
        server.route("GET", "/v1/partner/maps/place-details", Reply.ok("""
                {"result":{"geometry":{"location":{"lat":-1.2921,"lng":36.8219}},
                           "formatted_address":"Kilimani, Nairobi, Kenya","name":"Kilimani"}}
                """));

        Optional<PlaceDetails> details = client.placeDetails("p1");

        assertThat(details).isPresent();
        assertThat(details.get().latitude()).isEqualByComparingTo("-1.2921");
        assertThat(details.get().longitude()).isEqualByComparingTo("36.8219");
        assertThat(details.get().address()).isEqualTo("Kilimani, Nairobi, Kenya");
        assertThat(server.requestsTo("GET", "/v1/partner/maps/place-details").get(0).rawQuery()).isEqualTo("place_id=p1");
    }

    @Test
    void aPlaceWithNoGeometryIsReportedAsNotFound() {
        server.route("GET", "/v1/partner/maps/place-details", Reply.ok("{\"result\":{\"name\":\"Somewhere\"}}"));

        assertThat(client.placeDetails("p1")).isEmpty();
    }
}
