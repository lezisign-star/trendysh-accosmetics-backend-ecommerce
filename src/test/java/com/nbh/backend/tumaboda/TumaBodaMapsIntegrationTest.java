package com.nbh.backend.tumaboda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.tumaboda.FakeTumaBodaServer.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The address-search side of TumaBoda, against {@link FakeTumaBodaServer}: seeded areas filling in behind
 * (or standing in for) TumaBoda's own live search, a seeded suggestion resolving straight from our own
 * table, and "use my location" falling back to the nearest seeded area when TumaBoda's reverse-geocode is
 * unavailable. {@link TumaBodaIntegrationTest} covers quoting/booking/webhooks; this is the maps half, kept
 * separate so that file doesn't grow past what one person can hold in their head at once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TumaBodaMapsIntegrationTest {

    private static final FakeTumaBodaServer FAKE = FakeTumaBodaServer.startUnchecked();

    @DynamicPropertySource
    static void tumaboda(DynamicPropertyRegistry registry) {
        registry.add("app.tumaboda.api-key", () -> "maps-integration-test-key");
        registry.add("app.tumaboda.base-url", FAKE::baseUrl);
    }

    @AfterAll
    static void stopFake() {
        FAKE.close();
    }

    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    /** Every seeded area with a fixed, known id so a test can address a specific one directly by placeId. */
    private String kilimaniAreaId;

    @BeforeEach
    void setUp() {
        FAKE.reset();
        kilimaniAreaId = "area:" + jdbc.queryForObject(
                "select id from common_delivery_area where name = 'Kilimani'", java.util.UUID.class);
    }

    @AfterEach
    void tearDown() {
        FAKE.reset();
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Goes through MockMvc's own {@code .param()} rather than hand-building a query string, so a value
     *  containing a colon (a synthetic {@code area:<uuid>} id) is encoded exactly once, correctly. */
    private JsonNode getJson(String path, String paramName, String paramValue) throws Exception {
        MvcResult result = mvc.perform(get(path).param(paramName, paramValue)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void seededAreasAreSeededOnBoot() {
        Integer count = jdbc.queryForObject("select count(*) from common_delivery_area", Integer.class);
        assertThat(count).isGreaterThan(20);
    }

    @Test
    void aLiveResultAndASeededMatchAreBothOfferedWithLiveLeading() throws Exception {
        FAKE.route("GET", "/v1/partner/maps/autocomplete", req -> Reply.ok("""
                {"predictions":[{"place_id":"ChIJ-real-kilimani-mall","description":"Kilimani Mall, Nairobi"}]}
                """));

        JsonNode results = getJson("/api/v1/public/tumaboda/maps/autocomplete?input=kilimani");

        assertThat(results.get(0).get("description").asText()).isEqualTo("Kilimani Mall, Nairobi");
        assertThat(results.findValuesAsText("description")).anyMatch(d -> d.startsWith("Kilimani, Nairobi"));
    }

    @Test
    void aLiveFailureStillLeavesTheSeededMatchesStanding() throws Exception {
        FAKE.route("GET", "/v1/partner/maps/autocomplete", Reply.error(503, "UPSTREAM_DOWN", "TumaBoda's maps are down"));

        JsonNode results = getJson("/api/v1/public/tumaboda/maps/autocomplete?input=kilimani");

        assertThat(results.findValuesAsText("description")).anyMatch(d -> d.startsWith("Kilimani, Nairobi"));
    }

    @Test
    void quickPicksAreAlwaysFromTheSeededListNeverTumaBodaItself() throws Exception {
        JsonNode picks = getJson("/api/v1/public/tumaboda/maps/common-areas");

        assertThat(picks.size()).isGreaterThan(0);
        assertThat(picks.findValuesAsText("description")).anyMatch(d -> d.startsWith("Kilimani"));
        assertThat(FAKE.requests()).isEmpty();
    }

    @Test
    void aSeededSuggestionResolvesStraightFromOurOwnTableWithNoCallToTumaBoda() throws Exception {
        JsonNode details = getJson("/api/v1/public/tumaboda/maps/place-details", "placeId", kilimaniAreaId);

        assertThat(details.get("address").asText()).isEqualTo("Kilimani, Nairobi");
        assertThat(details.get("latitude").asDouble()).isEqualTo(-1.2921);
        assertThat(FAKE.requestsTo("GET", "/v1/partner/maps/place-details")).isEmpty();
    }

    @Test
    void reverseGeocodeUsesTumaBodasOwnAnswerWhenItHasOne() throws Exception {
        FAKE.route("GET", "/v1/partner/geocode", req -> Reply.ok("""
                {"location":{"latitude":-1.2921,"longitude":36.7820,"placeId":"ChIJ-real","formattedAddress":"Kilimani Road, Nairobi"}}
                """));

        JsonNode details = getJson("/api/v1/public/tumaboda/maps/reverse-geocode?lat=-1.2921&lng=36.7820");

        assertThat(details.get("address").asText()).isEqualTo("Kilimani Road, Nairobi");
    }

    @Test
    void reverseGeocodeFallsBackToTheNearestSeededAreaWhenTumaBodaFindsNothing() throws Exception {
        FAKE.route("GET", "/v1/partner/geocode", Reply.ok("{\"location\":null}"));

        // A point right next to the seeded Kilimani coordinate.
        JsonNode details = getJson("/api/v1/public/tumaboda/maps/reverse-geocode?lat=-1.2925&lng=36.7825");

        assertThat(details.get("address").asText()).contains("Kilimani");
        // The caller's own coordinates are echoed back, never snapped to the seeded area's.
        assertThat(details.get("latitude").asDouble()).isEqualTo(-1.2925);
    }

    @Test
    void reverseGeocodeAnswersWithNoLabelRatherThanFailingWhenNothingCanBeResolved() throws Exception {
        FAKE.route("GET", "/v1/partner/geocode", Reply.error(503, "UPSTREAM_DOWN", "down"));

        // Mombasa — nowhere near any seeded Nairobi-metro area.
        JsonNode details = getJson("/api/v1/public/tumaboda/maps/reverse-geocode?lat=-4.0435&lng=39.6682");

        assertThat(details.get("address").isNull()).isTrue();
        assertThat(details.get("latitude").asDouble()).isEqualTo(-4.0435);
    }

    @Test
    void reverseGeocodeRefusesAPointOutsideKenya() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/public/tumaboda/maps/reverse-geocode?lat=51.5074&lng=-0.1278")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

}
