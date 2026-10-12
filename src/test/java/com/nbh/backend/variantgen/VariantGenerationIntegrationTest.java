package com.nbh.backend.variantgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Variant generation end to end through the real runner, against stand-ins for Gemini (text + photos) and Cloudinary. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VariantGenerationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ImageCleanupConfig config;
    @Autowired private IntegrationSettingsService integrations;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ProductImageRepository images;
    @Autowired private com.nbh.backend.catalog.repository.ProductOptionValueRepository optionRows;

    private HttpServer stub;
    private final AtomicInteger imageCalls = new AtomicInteger();
    private final AtomicInteger textCalls = new AtomicInteger();
    private volatile String lastImagePrompt = "";
    private String originalGeminiUrl;
    private String originalCloudinaryUrl;
    private String token;
    private String png;
    private byte[] pngBytes;

    @BeforeEach
    void setUp() throws Exception {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        pngBytes = out.toByteArray();
        png = Base64.getEncoder().encodeToString(pngBytes);

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] reply;
            String type = "application/json";
            if (path.endsWith("/photo.png")) {
                reply = pngBytes;
                type = "image/png";
            } else if (path.contains("gemini-2.5-flash-image")) {
                imageCalls.incrementAndGet();
                lastImagePrompt = request;
                reply = ("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\","
                        + "\"data\":\"" + png + "\"}}]}}]}").getBytes(StandardCharsets.UTF_8);
            } else if (path.contains(":generateContent")) {
                textCalls.incrementAndGet();
                String answer;
                if (request.contains("list the colours and the sizes")) {
                    answer = "{\"colors\":[\"Green\",\"Blue\"],\"sizes\":[]}";
                } else {
                    answer = "{\"variants\":[{\"index\":0,\"swatchHex\":\"#1A7F4B\",\"description\":\"The first one.\"},"
                            + "{\"index\":1,\"swatchHex\":\"not a colour\",\"description\":\"The second one.\"}]}";
                }
                reply = json.writeValueAsBytes(java.util.Map.of("candidates", java.util.List.of(java.util.Map.of(
                        "content", java.util.Map.of("parts", java.util.List.of(java.util.Map.of("text", answer)))))));
            } else if (path.endsWith("/image/upload")) {
                String id = "v-" + UUID.randomUUID();
                reply = ("{\"secure_url\":\"https://res.cloudinary.com/test/" + id + ".png\",\"public_id\":\"" + id + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                reply = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        stub.start();
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        originalGeminiUrl = config.getGeminiBaseUrl();
        originalCloudinaryUrl = config.getCloudinaryUploadBaseUrl();
        config.setGeminiBaseUrl(base);
        config.setCloudinaryUploadBaseUrl(base);
        integrations.set(IntegrationKey.GEMINI_API_KEY, "test-key", null);
        integrations.set(IntegrationKey.CLOUDINARY_CLOUD_NAME, "test", null);
        integrations.set(IntegrationKey.CLOUDINARY_API_KEY, "k", null);
        integrations.set(IntegrationKey.CLOUDINARY_API_SECRET, "s", null);
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
        config.setGeminiBaseUrl(originalGeminiUrl);
        config.setCloudinaryUploadBaseUrl(originalCloudinaryUrl);
        for (IntegrationKey key : IntegrationKey.values()) {
            integrations.clear(key);
        }
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + token);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private record Made(String productId, String variantId, String name) {}

    private Made product(boolean withPhoto) throws Exception {
        String name = "Variant Gen " + UUID.randomUUID().toString().substring(0, 8);
        JsonNode p = ok(jsonBody(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],"
                + "\"published\":true,\"description\":\"A granite pot set.\","
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"12800\",\"isActive\":true}]}"));
        Made made = new Made(p.get("id").asText(), p.get("variants").get(0).get("id").asText(), name);
        if (withPhoto) {
            addPhoto(made);
        }
        return made;
    }

    private void addPhoto(Made made) {
        ProductImage photo = new ProductImage();
        photo.setProduct(products.findById(UUID.fromString(made.productId())).orElseThrow());
        photo.setVariant(variants.findById(UUID.fromString(made.variantId())).orElseThrow());
        photo.setUrl("http://127.0.0.1:" + stub.getAddress().getPort() + "/photo.png");
        photo.setPrimary(true);
        photo.setSource(ProductImage.ImageSource.UPLOAD);
        images.saveAndFlush(photo);
    }

    private JsonNode finished(String batchId) throws Exception {
        for (int i = 0; i < 160; i++) {
            JsonNode run = ok(get("/api/v1/admin/variant-generation/runs/" + batchId));
            if (!"IN_PROGRESS".equals(run.get("batch").get("status").asText())) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the run never finished");
    }

    @Test
    void colourNamesInThePromptBecomeLiveVariantsWithNamesDescriptionsSwatchesPriceAndPhotos() throws Exception {
        Made made = product(true);
        JsonNode started = ok(jsonBody(post("/api/v1/admin/variant-generation/run"), "{\"productId\":\"" + made.productId()
                + "\",\"prompt\":\"also in green and blue\",\"price\":\"13000\",\"baseOption\":\"Dusty\"}"));
        JsonNode run = finished(started.get("id").asText());

        assertThat(run.get("batch").get("status").asText()).isEqualTo("COMPLETED");
        JsonNode made2 = run.get("variants");
        assertThat(made2).hasSize(2);
        assertThat(made2.get(0).get("variantName").asText()).isEqualTo(made.name() + " – Green");
        assertThat(made2.get(0).get("optionLabel").asText()).isEqualTo("Green");
        assertThat(made2.get(0).get("swatchHex").asText()).isEqualTo("#1A7F4B");
        assertThat(made2.get(1).get("swatchHex").isNull()).as("an invalid hex from Gemini is dropped").isTrue();
        assertThat(made2.get(0).get("active").asBoolean()).isTrue();
        Set<String> skus = new HashSet<>();
        made2.forEach(v -> skus.add(v.get("sku").asText()));
        assertThat(skus).hasSize(2);

        var all = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(UUID.fromString(made.productId()));
        assertThat(all).hasSize(3);
        assertThat(all.get(0).getVariantName()).isEqualTo(made.name() + " – Dusty");
        for (var v : all.subList(1, 3)) {
            assertThat(v.getSellingPrice()).isEqualByComparingTo("13000");
            assertThat(v.getDescription()).isNotBlank();
            assertThat(v.getGeneratedFromVariantId()).isEqualTo(UUID.fromString(made.variantId()));
            assertThat(images.findByVariantIdOrderBySortOrderAsc(v.getId())).hasSize(1);
        }
        assertThat(imageCalls.get()).isEqualTo(2);
        assertThat(ok(get("/api/v1/admin/variant-generation/needs-images"))).isEmpty();

        // The admin order line / label names the colour, not the title twice.
        assertThat(variants.findWithProductById(all.get(1).getId()).orElseThrow().displayLabel()).isEqualTo(made.name() + " (Green)");

        // The product's colour list (V63) takes in the new colours in the order they were made; the base has none.
        assertThat(optionRows.findByProductIdOrderByKindAscPositionAsc(UUID.fromString(made.productId())).stream()
                .map(o -> o.getKind() + ":" + o.getValue() + ":" + o.getPosition() + ":" + o.getSwatchHex()).toList())
                .containsExactly("COLOR:Green:0:#1A7F4B", "COLOR:Blue:1:null");
    }

    @Test
    void approveFirstKeepsVariantsHiddenWithPhotoDraftsUntilTheRunIsPublished() throws Exception {
        Made made = product(true);
        JsonNode started = ok(jsonBody(post("/api/v1/admin/variant-generation/run"), "{\"productId\":\"" + made.productId()
                + "\",\"colors\":[\"Green\",\"Blue\"],\"sizes\":[],\"approveFirst\":true,"
                + "\"prompt\":\"label says 5 pcs\"}"));
        JsonNode run = finished(started.get("id").asText());
        assertThat(run.get("batch").get("pendingDrafts").asInt()).isEqualTo(2);
        assertThat(run.get("variants")).hasSize(2);
        run.get("variants").forEach(v -> assertThat(v.get("active").asBoolean()).isFalse());
        assertThat(lastImagePrompt).contains("label says 5 pcs");

        JsonNode published = ok(post("/api/v1/admin/variant-generation/runs/" + started.get("id").asText() + "/publish"));
        assertThat(published.get("published").asInt()).isEqualTo(2);
        run = ok(get("/api/v1/admin/variant-generation/runs/" + started.get("id").asText()));
        run.get("variants").forEach(v -> assertThat(v.get("active").asBoolean()).isTrue());
    }

    @Test
    void aBaseWithoutAPhotoStillMakesTheVariantsAndTheyWaitInNeedsImagesUntilRetried() throws Exception {
        Made made = product(false);
        JsonNode started = ok(jsonBody(post("/api/v1/admin/variant-generation/run"), "{\"productId\":\"" + made.productId()
                + "\",\"colors\":[\"Green\"]}"));
        JsonNode run = finished(started.get("id").asText());
        assertThat(run.get("batch").get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.get("variants")).hasSize(1);
        assertThat(imageCalls.get()).isZero();

        JsonNode needing = ok(get("/api/v1/admin/variant-generation/needs-images"));
        assertThat(needing.toString()).contains(made.name());

        addPhoto(made);
        JsonNode retry = ok(jsonBody(post("/api/v1/admin/variant-generation/needs-images/run"), "{}"));
        JsonNode retried = finished(retry.get("id").asText());
        assertThat(retried.get("batch").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(ok(get("/api/v1/admin/variant-generation/needs-images")).toString()).doesNotContain(made.name());
    }

    @Test
    void aRerunDoesNotDuplicateOptionsTheProductAlreadyHas() throws Exception {
        Made made = product(true);
        String body = "{\"productId\":\"" + made.productId() + "\",\"colors\":[\"Green\"]}";
        finished(ok(jsonBody(post("/api/v1/admin/variant-generation/run"), body)).get("id").asText());
        JsonNode second = finished(ok(jsonBody(post("/api/v1/admin/variant-generation/run"), body)).get("id").asText());
        assertThat(second.get("batch").get("status").asText()).isEqualTo("FAILED");
        assertThat(variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(UUID.fromString(made.productId()))).hasSize(2);
    }
}
