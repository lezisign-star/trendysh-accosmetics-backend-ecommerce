package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.integrations.IntegrationKey;
import com.nbh.backend.integrations.IntegrationSettingsService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Descriptions drafted from a product's photo: written to the character limits whatever Gemini returns, held for
 * approval (never reaching the shop until then), skipped for products that already have text, shown on the public
 * product page once approved, and visible per batch next to the photos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DescriptionDraftsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ImageCleanupConfig config;
    @Autowired private IntegrationSettingsService integrations;
    @Autowired private DescriptionDraftService descriptions;
    @Autowired private ImageCleanupBatchRepository batches;
    @Autowired private ImageDraftRepository imageDrafts;
    @Autowired private ImageCleanupRunner runner;
    @Autowired private com.nbh.backend.catalog.repository.ProductRepository productRepository;

    private HttpServer gemini;
    private final AtomicInteger geminiCalls = new AtomicInteger();
    private volatile String geminiBody;
    private volatile String geminiRequest = "";
    private String originalBaseUrl;
    private String token;

    private static final GeminiImageClient.Image PHOTO = new GeminiImageClient.Image(new byte[]{1, 2, 3}, "image/jpeg");

    @BeforeEach
    void setUp() throws Exception {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);

        gemini = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gemini.createContext("/", exchange -> {
            geminiRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            geminiCalls.incrementAndGet();
            byte[] out = geminiBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        gemini.start();
        originalBaseUrl = config.getGeminiBaseUrl();
        config.setGeminiBaseUrl("http://127.0.0.1:" + gemini.getAddress().getPort());
        integrations.set(IntegrationKey.GEMINI_API_KEY, "test-key", null);
        geminiCalls.set(0);
    }

    @AfterEach
    void tearDown() {
        gemini.stop(0);
        config.setGeminiBaseUrl(originalBaseUrl);
        integrations.clear(IntegrationKey.GEMINI_API_KEY);
    }

    /** Gemini's reply shape: the answer is itself JSON text inside the first part. */
    private static String reply(String answerJson) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(answerJson) + "}]}}]}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + token);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private record Made(String productId, String variantId, String name) {}

    private Made product(String description) throws Exception {
        String name = "Describe Test " + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + (description == null ? "" : "\"description\":\"" + description + "\",")
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}";
        JsonNode p = ok(jsonBody(post("/api/v1/products/draft"), body));
        return new Made(p.get("id").asText(), p.get("variants").get(0).get("id").asText(), name);
    }

    private static String longText(int length) {
        return ("lovely word ".repeat(length / 12 + 2)).substring(0, length);
    }

    @Test
    void textIsCutToTheLimitsAtAWordBoundaryAndNothingReachesTheShopUntilApproved() throws Exception {
        Made made = product(null);
        geminiBody = reply("{\"shortDescription\":\"" + longText(300) + "\",\"description\":\"" + longText(1500)
                + "\",\"highlights\":[\"One\",\"Two\",\"Three\",\"Four\",\"Five\",\"Six\",\"" + longText(200) + "\"],"
                + "\"ingredients\":\"\",\"howToUse\":\"\",\"seoTitle\":\"" + longText(120)
                + "\",\"seoDescription\":\"" + longText(400) + "\"}");

        assertThat(descriptions.draftFor(null, UUID.fromString(made.productId()), PHOTO, null)).isTrue();

        JsonNode draft = null;
        for (JsonNode d : ok(get("/api/v1/admin/image-cleanup/descriptions"))) {
            if (made.productId().equals(d.get("productId").asText())) draft = d;
        }
        assertThat(draft).isNotNull();
        assertThat(draft.get("shortDescription").asText().length()).isLessThanOrEqualTo(DescriptionCaps.SHORT_DESCRIPTION);
        assertThat(draft.get("description").asText().length()).isLessThanOrEqualTo(DescriptionCaps.DESCRIPTION);
        assertThat(draft.get("seoTitle").asText().length()).isLessThanOrEqualTo(DescriptionCaps.SEO_TITLE);
        assertThat(draft.get("seoDescription").asText().length()).isLessThanOrEqualTo(DescriptionCaps.SEO_DESCRIPTION);
        assertThat(draft.get("highlights")).hasSize(DescriptionCaps.HIGHLIGHTS_COUNT);
        for (JsonNode h : draft.get("highlights")) {
            assertThat(h.asText().length()).isLessThanOrEqualTo(DescriptionCaps.HIGHLIGHT);
        }
        // Empty ingredients/how-to-use stay empty rather than being filled in.
        assertThat(draft.get("ingredients").isNull()).isTrue();

        // Not on the product yet.
        JsonNode before = ok(get("/api/v1/products/" + made.productId()));
        assertThat(before.path("description").isNull() || before.path("description").asText().isBlank()).isTrue();
    }

    @Test
    void approvingCopiesTheTextOntoTheProductAndEditsThatAreTooLongAreRefused() throws Exception {
        Made made = product(null);
        geminiBody = reply("{\"shortDescription\":\"A tidy pot of acrylic powder.\",\"description\":\"Fine acrylic powder for nail extensions.\","
                + "\"highlights\":[\"Fine texture\",\"Clear finish\"],\"ingredients\":\"\",\"howToUse\":\"\","
                + "\"seoTitle\":\"Acrylic Powder\",\"seoDescription\":\"Acrylic powder for nail technicians.\"}");
        descriptions.draftFor(null, UUID.fromString(made.productId()), PHOTO, null);
        String id = draftId(made.productId());

        assertThat(status(jsonBody(post("/api/v1/admin/image-cleanup/descriptions/" + id + "/approve"),
                "{\"shortDescription\":\"" + longText(200) + "\"}"))).isEqualTo(422);

        ok(jsonBody(post("/api/v1/admin/image-cleanup/descriptions/" + id + "/approve"),
                "{\"shortDescription\":\"A tidy pot of acrylic powder (edited).\"}"));

        JsonNode product = ok(get("/api/v1/products/" + made.productId()));
        assertThat(product.get("shortDescription").asText()).isEqualTo("A tidy pot of acrylic powder (edited).");
        assertThat(product.get("description").asText()).isEqualTo("Fine acrylic powder for nail extensions.");
        // The draft is gone once approved.
        assertThat(draftIdOrNull(made.productId())).isNull();
    }

    @Test
    void aProductThatAlreadyHasADescriptionIsNeverRedraftedAndGeminiIsNotCalled() throws Exception {
        Made made = product("Written by a person.");
        geminiBody = reply("{}");
        assertThat(descriptions.draftFor(null, UUID.fromString(made.productId()), PHOTO, null)).isFalse();
        assertThat(geminiCalls.get()).isZero();
    }

    @Test
    void rejectingADraftDeletesItAndDraftingAgainReplacesTheWaitingOne() throws Exception {
        Made made = product(null);
        geminiBody = reply("{\"shortDescription\":\"First\",\"description\":\"First text.\",\"highlights\":[],"
                + "\"ingredients\":\"\",\"howToUse\":\"\",\"seoTitle\":\"First\",\"seoDescription\":\"First.\"}");
        descriptions.draftFor(null, UUID.fromString(made.productId()), PHOTO, null);
        geminiBody = reply("{\"shortDescription\":\"Second\",\"description\":\"Second text.\",\"highlights\":[],"
                + "\"ingredients\":\"\",\"howToUse\":\"\",\"seoTitle\":\"Second\",\"seoDescription\":\"Second.\"}");
        descriptions.draftFor(null, UUID.fromString(made.productId()), PHOTO, null);

        int waiting = 0;
        for (JsonNode d : ok(get("/api/v1/admin/image-cleanup/descriptions"))) {
            if (made.productId().equals(d.get("productId").asText())) {
                waiting++;
                assertThat(d.get("shortDescription").asText()).isEqualTo("Second");
            }
        }
        assertThat(waiting).isEqualTo(1);

        assertThat(status(delete("/api/v1/admin/image-cleanup/descriptions/" + draftId(made.productId())))).isEqualTo(200);
        assertThat(draftIdOrNull(made.productId())).isNull();
    }

    @Test
    void aBatchShowsItsPhotosAndDescriptionPerProduct() throws Exception {
        Made made = product(null);
        ImageCleanupBatch batch = new ImageCleanupBatch();
        batch.setStatus(ImageCleanupBatch.Status.COMPLETED);
        batch.setPublishMode(ImageCleanupBatch.PublishMode.REVIEW);
        batch.setWriteDescriptions(true);
        batch = batches.saveAndFlush(batch);

        ImageDraft photo = new ImageDraft();
        photo.setBatchId(batch.getId());
        photo.setProductId(UUID.fromString(made.productId()));
        photo.setVariantId(UUID.fromString(made.variantId()));
        photo.setReferenceImageId(UUID.randomUUID());
        photo.setViewKey("CLEAN");
        photo.setUrl("https://example.com/made-" + UUID.randomUUID() + ".png");
        imageDrafts.saveAndFlush(photo);

        geminiBody = reply("{\"shortDescription\":\"Short\",\"description\":\"Long text.\",\"highlights\":[\"A\"],"
                + "\"ingredients\":\"\",\"howToUse\":\"\",\"seoTitle\":\"T\",\"seoDescription\":\"D\"}");
        descriptions.draftFor(batch.getId(), UUID.fromString(made.productId()), PHOTO, null);

        JsonNode results = ok(get("/api/v1/admin/image-cleanup/batches/" + batch.getId() + "/results"));
        assertThat(results).hasSize(1);
        JsonNode row = results.get(0);
        assertThat(row.get("productName").asText()).isEqualTo(made.name());
        assertThat(row.get("options").get(0).get("photos")).hasSize(1);
        assertThat(row.get("options").get(0).get("photos").get(0).get("live").asBoolean()).isFalse();
        assertThat(row.get("description").get("shortDescription").asText()).isEqualTo("Short");

        JsonNode summary = ok(get("/api/v1/admin/image-cleanup/batches/" + batch.getId()));
        assertThat(summary.get("writeDescriptions").asBoolean()).isTrue();
        assertThat(summary.get("pendingDescriptions").asInt()).isEqualTo(1);

        // Undoing the batch discards the descriptions nobody approved.
        batches.findById(batch.getId()).ifPresent(b -> {
            b.setStatus(ImageCleanupBatch.Status.COMPLETED);
            batches.save(b);
        });
        descriptions.discardBatch(batch.getId());
        assertThat(draftIdOrNull(made.productId())).isNull();
    }

    private String draftId(String productId) throws Exception {
        String id = draftIdOrNull(productId);
        assertThat(id).as("a description draft for the product").isNotNull();
        return id;
    }

    private String draftIdOrNull(String productId) throws Exception {
        for (JsonNode d : ok(get("/api/v1/admin/image-cleanup/descriptions"))) {
            if (productId.equals(d.get("productId").asText())) return d.get("id").asText();
        }
        return null;
    }

    // ---- descriptions only (no pictures) ----

    private static final String GOOD_REPLY = "{\"shortDescription\":\"A neat pot.\",\"description\":\"Fine powder for nail work.\","
            + "\"highlights\":[\"Fine texture\"],\"ingredients\":\"\",\"howToUse\":\"\",\"seoTitle\":\"Powder\","
            + "\"seoDescription\":\"Powder for nails.\"}";

    @Test
    void aDescriptionsOnlyRunDraftsForProductsWithoutTextEvenWithNoPhotoAndMakesNoPictures() throws Exception {
        Made bare = product(null);
        Made written = product("Already written.");
        geminiBody = reply(GOOD_REPLY);

        // The candidate list: the bare product is in it, the one with text is not.
        var candidates = descriptions.withoutDescription(5000);
        assertThat(candidates).contains(UUID.fromString(bare.productId())).doesNotContain(UUID.fromString(written.productId()));
        assertThat(descriptions.countWithoutDescription()).isGreaterThanOrEqualTo(1);

        ImageCleanupBatch batch = new ImageCleanupBatch();
        batch.setImagesPerProduct(0);
        batch.setWriteDescriptions(true);
        batch.setPublishMode(ImageCleanupBatch.PublishMode.REVIEW);
        batch.setRequestedCount(2);
        batch = batches.saveAndFlush(batch);
        runner.runDescriptions(batch.getId(), java.util.List.of(UUID.fromString(bare.productId()),
                UUID.fromString(written.productId())), null);

        JsonNode done = null;
        for (int i = 0; i < 120; i++) {
            done = ok(get("/api/v1/admin/image-cleanup/batches/" + batch.getId()));
            if (!"IN_PROGRESS".equals(done.get("status").asText())) break;
            Thread.sleep(250);
        }
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("descriptionsDrafted").asInt()).isEqualTo(1);
        assertThat(done.get("pendingDrafts").asInt()).as("no pictures were made").isZero();
        assertThat(done.get("succeededCount").asInt()).isEqualTo(2);
        assertThat(geminiCalls.get()).as("Gemini is not asked about a product that has text").isEqualTo(1);
        assertThat(draftIdOrNull(bare.productId())).isNotNull();
        assertThat(draftIdOrNull(written.productId())).isNull();
    }

    @Test
    void oneProductCanBeDescribedOnDemandEvenWhenItHasTextAndTheProductItselfIsUnchanged() throws Exception {
        Made written = product("Written by a person.");
        geminiBody = reply(GOOD_REPLY);

        JsonNode draft = ok(jsonBody(post("/api/v1/admin/image-cleanup/descriptions/product/" + written.productId()),
                "{\"note\":\"15ml bottle\"}"));
        assertThat(draft.get("shortDescription").asText()).isEqualTo("A neat pot.");
        assertThat(geminiCalls.get()).isEqualTo(1);
        assertThat(geminiRequest).contains("15ml bottle");

        JsonNode product = ok(get("/api/v1/products/" + written.productId()));
        assertThat(product.get("description").asText()).isEqualTo("Written by a person.");
    }

    @Test
    void aMissingProductIsRefused() throws Exception {
        assertThat(status(jsonBody(post("/api/v1/admin/image-cleanup/descriptions/product/" + UUID.randomUUID()), "{}")))
                .isEqualTo(404);
    }
    @Test
    void runsCanBePagedThroughNewestFirstWithATotal() throws Exception {
        for (int i = 0; i < 3; i++) {
            ImageCleanupBatch b = new ImageCleanupBatch();
            b.setStatus(ImageCleanupBatch.Status.COMPLETED);
            batches.saveAndFlush(b);
        }
        JsonNode first = ok(get("/api/v1/admin/image-cleanup/batches/page").param("page", "0").param("size", "2"));
        assertThat(first.get("content")).hasSize(2);
        assertThat(first.get("totalElements").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode second = ok(get("/api/v1/admin/image-cleanup/batches/page").param("page", "1").param("size", "2"));
        assertThat(second.get("content").get(0).get("id").asText()).isNotEqualTo(first.get("content").get(0).get("id").asText());
        // newest first across the page boundary
        String a = first.get("content").get(1).get("createdAt").asText();
        String b = second.get("content").get(0).get("createdAt").asText();
        assertThat(java.time.Instant.parse(a)).isAfterOrEqualTo(java.time.Instant.parse(b));

        JsonNode beyond = ok(get("/api/v1/admin/image-cleanup/batches/page").param("page", "9999").param("size", "2"));
        assertThat(beyond.get("content")).isEmpty();
    }

}
