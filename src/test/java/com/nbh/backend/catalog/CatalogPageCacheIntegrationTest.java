package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.service.CatalogPageCache;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The storefront's catalogue pages over real HTTP with the page cache switched ON: the validator and cache
 * headers a browser needs for a bodiless {@code 304}, that one build serves a burst of identical requests,
 * and that a real admin change (publishing a product) is visible on the very next request rather than after
 * the time limit.
 *
 * <p>The general suite runs with the cache off (see {@code application-test.yml}: many tests change data with
 * a direct database write, which announces nothing, and then read the storefront straight away). This class
 * turns it on for its own tests and puts it back afterwards. It runs in the shared Spring context rather than
 * its own because every distinct context keeps a database connection pool open for the rest of the run, and
 * Postgres only has so many to give. "Was the database asked?" is answered with Hibernate's statement
 * counter (enabled for the suite), which needs no spy bean — a spy would also force a context of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogPageCacheIntegrationTest {

    private static final String PAGE = "/api/v1/catalog/variants?size=5&page=0";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CatalogPageCache cache;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private String staffToken;
    private long originalTtl;

    @BeforeEach
    void switchTheCacheOnAndSignIn() {
        originalTtl = (long) ReflectionTestUtils.getField(cache, "ttlMs");
        ReflectionTestUtils.setField(cache, "ttlMs", 60_000L);
        cache.invalidate();
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    @AfterEach
    void putTheCacheBackAsTheSuiteExpectsIt() {
        ReflectionTestUtils.setField(cache, "ttlMs", originalTtl);
        cache.invalidate();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private JsonNode asStaff(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private record Published(String productId, String variantId, String name) {}

    /** A published, in-stock product, so it shows up as the newest card on the storefront. */
    private Published publishNewProduct(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = asStaff(post("/api/v1/products/draft"), body);
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        asStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock"),
                "{\"quantity\":10,\"unitCost\":100}");
        asStaff(post("/api/v1/products/" + productId + "/publish"), null);
        return new Published(productId, variantId, name);
    }

    private MvcResult browse(String url, String ifNoneMatch) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        if (ifNoneMatch != null) request.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        return mvc.perform(request).andReturn();
    }

    @Test
    void aBrowsingPageCarriesAWeakValidatorAndAHeaderThatLetsTheBrowserKeepIt() throws Exception {
        MvcResult result = browse(PAGE, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).startsWith("W/\"");
        String cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        assertThat(cacheControl).as("no-store (Spring Security's default) would stop the browser ever revalidating")
                .contains("no-cache").doesNotContain("no-store");
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(json.readTree(result.getResponse().getContentAsString()).has("content")).isTrue();
    }

    @Test
    void aBrowserHoldingTheSameBytesGetsABodilessNotModified() throws Exception {
        String etag = browse(PAGE, null).getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult again = browse(PAGE, etag);

        assertThat(again.getResponse().getStatus()).isEqualTo(304);
        assertThat(again.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(again.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(etag);
    }

    @Test
    void aValidatorFromAnOlderVersionOfThePageGetsTheFullPage() throws Exception {
        MvcResult result = browse(PAGE, "W/\"not-the-current-page\"");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isNotBlank();
    }

    @Test
    void aBurstOfIdenticalRequestsNeverTouchesTheDatabaseOnceThePageIsBuilt() throws Exception {
        browse(PAGE, null); // build the page and let it settle before counting
        long before = statistics().getPrepareStatementCount();

        for (int i = 0; i < 30; i++) {
            assertThat(browse(PAGE, null).getResponse().getStatus()).isEqualTo(200);
        }

        assertThat(statistics().getPrepareStatementCount() - before)
                .as("thirty visitors, zero database statements").isZero();
    }

    @Test
    void publishingAProductShowsOnTheNextRequestNotAfterTheTimeLimit() throws Exception {
        MvcResult before = browse(PAGE, null);
        String oldEtag = before.getResponse().getHeader(HttpHeaders.ETAG);
        String name = "Cache Invalidation " + UUID.randomUUID().toString().substring(0, 8);
        assertThat(before.getResponse().getContentAsString()).doesNotContain(name);

        publishNewProduct(name);

        // The old validator no longer matches: a browser holding the old page gets the new one, not a 304.
        MvcResult after = browse(PAGE, oldEtag);
        assertThat(after.getResponse().getStatus()).as("the page changed, so it must not be 304").isEqualTo(200);
        assertThat(after.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(oldEtag);
        assertThat(after.getResponse().getContentAsString()).contains(name);
    }

    /**
     * Publishing is announced by an explicit event; a stock edit is not — it is caught by the entity listener
     * on every write, which is the path that covers price and stock edits, checkouts and imports alike.
     */
    @Test
    void aStockEditShowsOnTheNextRequestThroughTheEntityListenerToo() throws Exception {
        Published product = publishNewProduct("Cache Stock " + UUID.randomUUID().toString().substring(0, 8));
        MvcResult before = browse(PAGE, null);
        String oldEtag = before.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(before.getResponse().getContentAsString()).contains(product.name());

        asStaff(put("/api/v1/products/" + product.productId() + "/variants/" + product.variantId() + "/stock"),
                "{\"quantity\":7,\"unitCost\":100}");

        MvcResult after = browse(PAGE, oldEtag);
        assertThat(after.getResponse().getStatus()).as("stock changed, so the page did too: not a 304").isEqualTo(200);
        assertThat(after.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(oldEtag);
        assertThat(after.getResponse().getContentAsString()).contains(product.name());
    }

    @Test
    void sortedOversizedFarOffOrOddlyCategorisedRequestsAreNeverCachedOrTagged() throws Exception {
        // Each of these is answered exactly as before the cache existed: the cache's memory and key space stay
        // bounded however many odd URLs anonymous visitors send.
        for (String url : new String[] {
                "/api/v1/catalog/variants?size=5&page=0&sort=sellingPrice,asc",
                "/api/v1/catalog/variants?size=501&page=0",
                "/api/v1/catalog/variants?size=5&page=1000",
                "/api/v1/catalog/variants?size=5&page=0&category=" + "x".repeat(81)}) {
            MvcResult result = browse(url, null);

            assertThat(result.getResponse().getStatus()).as(url).isEqualTo(200);
            assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).as(url).isNull();
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).as(url).contains("no-store");
        }
    }

    @Test
    void aFreeTextSearchIsNeverCachedOrTagged() throws Exception {
        String search = "/api/v1/catalog/variants?q=anything&size=5&page=0";
        browse(search, null); // warm anything that is about to be counted (Hibernate metadata, query plans)

        long beforeFirst = statistics().getPrepareStatementCount();
        MvcResult first = browse(search, null);
        long afterFirst = statistics().getPrepareStatementCount();
        MvcResult second = browse(search, null);
        long afterSecond = statistics().getPrepareStatementCount();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(first.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).as("left to the security default")
                .contains("no-store");
        assertThat(afterFirst - beforeFirst).as("the first search hit the database").isPositive();
        assertThat(afterSecond - afterFirst).as("so did the second: nothing was cached").isPositive();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theCachedBodyIsTheSameShapeTheStorefrontAlwaysGot() throws Exception {
        JsonNode page = json.readTree(browse(PAGE, null).getResponse().getContentAsString());

        assertThat(page.fieldNames()).toIterable().contains("content", "totalElements", "totalPages", "last", "size", "number");
        if (page.get("content").size() > 0) {
            assertThat(page.get("content").get(0).fieldNames()).toIterable().contains("variantId", "productName", "price");
        }
    }
}
