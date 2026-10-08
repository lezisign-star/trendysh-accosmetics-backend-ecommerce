package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A whole photo batch through the real runner against stand-ins for Gemini (photos and text), Cloudinary and the
 * product's own photo: with "also write descriptions" ticked it makes the photo drafts AND one description draft per
 * product, shows both on the batch's results, and leaves a product that already has text alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BatchWithDescriptionsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ImageCleanupConfig config;
    @Autowired private IntegrationSettingsService integrations;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ProductImageRepository images;

    private HttpServer stub;
    private final AtomicInteger textCalls = new AtomicInteger();
    private final AtomicInteger imageCalls = new AtomicInteger();
    private String originalGeminiUrl;
    private String originalCloudinaryUrl;
    private String token;
    private String png;

    @BeforeEach
    void setUp() throws Exception {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);

        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        byte[] pngBytes = out.toByteArray();
        png = Base64.getEncoder().encodeToString(pngBytes);

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            byte[] reply;
            String type = "application/json";
            if (path.endsWith("/photo.png")) {
                reply = pngBytes;
                type = "image/png";
            } else if (path.contains("gemini-2.5-flash-image")) {
                imageCalls.incrementAndGet();
                reply = ("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\","
                        + "\"data\":\"" + png + "\"}}]}}]}").getBytes(StandardCharsets.UTF_8);
            } else if (path.contains(":generateContent")) {
                textCalls.incrementAndGet();
                String answer = "{\\\"shortDescription\\\":\\\"A neat pot of powder.\\\",\\\"description\\\":\\\"Fine powder for nail work.\\\","
                        + "\\\"highlights\\\":[\\\"Fine texture\\\"],\\\"ingredients\\\":\\\"\\\",\\\"howToUse\\\":\\\"\\\","
                        + "\\\"seoTitle\\\":\\\"Powder\\\",\\\"seoDescription\\\":\\\"Powder for nails.\\\"}";
                reply = ("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + answer + "\"}]}}]}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/image/upload")) {
                String id = "p-" + UUID.randomUUID();
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

    /** A product whose only option has a real photo (served by the stand-in). */
    private Made productWithPhoto(String description) throws Exception {
        String name = "Batch Describe " + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + (description == null ? "" : "\"description\":\"" + description + "\",")
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}";
        JsonNode p = ok(jsonBody(post("/api/v1/products/draft"), body));
        String productId = p.get("id").asText();
        String variantId = p.get("variants").get(0).get("id").asText();
        ProductImage photo = new ProductImage();
        photo.setProduct(products.findById(UUID.fromString(productId)).orElseThrow());
        photo.setVariant(variants.findById(UUID.fromString(variantId)).orElseThrow());
        photo.setUrl("http://127.0.0.1:" + stub.getAddress().getPort() + "/photo.png");
        photo.setPrimary(true);
        photo.setSource(ProductImage.ImageSource.UPLOAD);
        images.saveAndFlush(photo);
        return new Made(productId, variantId, name);
    }

    private JsonNode finished(String batchId) throws Exception {
        for (int i = 0; i < 120; i++) {
            JsonNode batch = ok(get("/api/v1/admin/image-cleanup/batches/" + batchId));
            if (!"IN_PROGRESS".equals(batch.get("status").asText())) {
                return batch;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the batch never finished");
    }

    @Test
    void aBatchWithDescriptionsMakesPhotoDraftsAndOneDescriptionDraftPerProduct() throws Exception {
        Made made = productWithPhoto(null);
        JsonNode started = ok(jsonBody(post("/api/v1/admin/image-cleanup/run/" + made.productId()),
                "{\"views\":[\"CLEAN\"],\"describe\":true}"));
        assertThat(started.get("writeDescriptions").asBoolean()).isTrue();

        JsonNode batch = finished(started.get("id").asText());
        assertThat(batch.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(batch.get("descriptionsDrafted").asInt()).isEqualTo(1);
        assertThat(batch.get("pendingDescriptions").asInt()).isEqualTo(1);
        assertThat(batch.get("pendingDrafts").asInt()).isEqualTo(1);
        assertThat(textCalls.get()).isEqualTo(1);
        assertThat(imageCalls.get()).isEqualTo(1);

        JsonNode results = ok(get("/api/v1/admin/image-cleanup/batches/" + started.get("id").asText() + "/results"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("productName").asText()).isEqualTo(made.name());
        assertThat(results.get(0).get("options").get(0).get("photos")).hasSize(1);
        assertThat(results.get(0).get("options").get(0).get("referenceUrl").asText()).endsWith("/photo.png");
        assertThat(results.get(0).get("description").get("shortDescription").asText()).isEqualTo("A neat pot of powder.");
    }

    @Test
    void aBatchWithoutTheTickWritesNoDescriptionAndAProductWithTextIsLeftAlone() throws Exception {
        Made plain = productWithPhoto(null);
        JsonNode first = finished(ok(jsonBody(post("/api/v1/admin/image-cleanup/run/" + plain.productId()),
                "{\"views\":[\"CLEAN\"]}")).get("id").asText());
        assertThat(first.get("descriptionsDrafted").asInt()).isZero();
        assertThat(textCalls.get()).isZero();

        Made written = productWithPhoto("Already written by a person.");
        JsonNode second = finished(ok(jsonBody(post("/api/v1/admin/image-cleanup/run/" + written.productId()),
                "{\"views\":[\"CLEAN\"],\"describe\":true}")).get("id").asText());
        assertThat(second.get("descriptionsDrafted").asInt()).isZero();
        assertThat(textCalls.get()).as("no AI text for a product that already has some").isZero();
        assertThat(second.get("pendingDrafts").asInt()).isEqualTo(1); // its photo was still made
    }
}
