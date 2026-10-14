package com.nbh.backend.variantjobs;

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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reading the colours of a picture and editing a variant's photo with a prompt (owner, 2026-10-14), through the real
 * endpoints, against stand-ins for Gemini (text with a photo, and photo editing) and Cloudinary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoToolsIntegrationTest {

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
    private final List<String> textRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> imagePrompts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger uploads = new AtomicInteger();
    /** What the text stand-in answers: the colours JSON, QUOTA (429), or MESS (not the shape asked for). */
    private volatile String textMode = "COLOURS";
    private volatile String imageMode = "OK";
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
        stub.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        stub.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] reply;
            int status = 200;
            String type = "application/json";
            if (path.endsWith(".png") && !path.contains("upload")) {
                reply = pngBytes;
                type = "image/png";
            } else if (path.contains("gemini-2.5-flash-image")) {
                imagePrompts.add(request);
                if ("QUOTA".equals(imageMode)) {
                    status = 429;
                    reply = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}".getBytes(StandardCharsets.UTF_8);
                } else {
                    reply = ("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\","
                            + "\"data\":\"" + png + "\"}}]}}]}").getBytes(StandardCharsets.UTF_8);
                }
            } else if (path.contains(":generateContent")) {
                textRequests.add(request);
                if ("QUOTA".equals(textMode)) {
                    status = 429;
                    reply = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}".getBytes(StandardCharsets.UTF_8);
                } else {
                    String answer = "MESS".equals(textMode)
                            ? "{\"colours\":[{\"name\":\"\",\"hex\":\"red\",\"code\":\"No. 7\",\"count\":0}],\"sizes\":[]}"
                            : "{\"colours\":["
                            + "{\"name\":\"Ruby Red\",\"hex\":\"#9b111e\",\"code\":\"DH 03\",\"count\":2},"
                            + "{\"name\":\"ruby red\",\"hex\":\"\",\"code\":\"dh 03\",\"count\":1},"
                            + "{\"name\":\"Nude Beige\",\"hex\":\"C8A28A\",\"code\":\"\",\"count\":1},"
                            + "{\"name\":\"A very long shade name that goes on and on and on for far too long\","
                            + "\"hex\":\"#2E5EAA\",\"code\":\"KE32\",\"count\":3},"
                            + "{\"name\":\"Mystery\",\"hex\":\"not a colour\",\"code\":\"\",\"count\":1}],"
                            + "\"sizes\":[\"50ml\",\"50 ML\",\"15g\"],\"note\":\"Five bottles on a white table.\"}";
                    reply = json.writeValueAsBytes(Map.of("candidates", List.of(Map.of(
                            "content", Map.of("parts", List.of(Map.of("text", answer)))))));
                }
            } else if (path.endsWith("/image/upload")) {
                String id = "p-" + UUID.randomUUID();
                uploads.incrementAndGet();
                // Pointed back at this stand-in, so a photo that was just stored can be downloaded and edited again.
                reply = ("{\"secure_url\":\"http://127.0.0.1:" + stub.getAddress().getPort() + "/" + id
                        + ".png\",\"public_id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
            } else {
                reply = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, reply.length);
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

    // ---------------------------------------------------------------- helpers

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode readPhoto(String image, String mime, int expectedStatus) throws Exception {
        String body = json.writeValueAsString(Map.of("image", image, "mimeType", mime, "productName", "Roniki Gel Pink"));
        MvcResult r = call(post("/api/v1/admin/variant-jobs/read-photo"), body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private record Made(UUID productId, UUID variantId) {}

    private Made productWithPhoto(boolean withPhoto) throws Exception {
        String name = "Edit Polish " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = call(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],"
                + "\"published\":true,\"description\":\"A gel polish.\","
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode p = json.readTree(r.getResponse().getContentAsString());
        Made made = new Made(UUID.fromString(p.get("id").asText()),
                UUID.fromString(p.get("variants").get(0).get("id").asText()));
        if (withPhoto) {
            ProductImage photo = new ProductImage();
            photo.setProduct(products.findById(made.productId()).orElseThrow());
            photo.setVariant(variants.findById(made.variantId()).orElseThrow());
            photo.setUrl("http://127.0.0.1:" + stub.getAddress().getPort() + "/photo-" + UUID.randomUUID() + ".png");
            photo.setPrimary(true);
            photo.setSource(ProductImage.ImageSource.UPLOAD);
            images.saveAndFlush(photo);
        }
        return made;
    }

    private MvcResult edit(Made m, UUID imageId, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", m.productId().toString());
        body.put("variantId", m.variantId().toString());
        body.put("imageId", imageId == null ? null : imageId.toString());
        body.put("note", note);
        return call(post("/api/v1/admin/variant-jobs/photo-edit"), json.writeValueAsString(body));
    }

    // ---------------------------------------------------------------- reading a picture

    @Test
    void aPictureOfVariantsComesBackAsATidyListToTick() throws Exception {
        JsonNode out = readPhoto(png, "image/png", 200);
        JsonNode colours = out.get("colours");
        // "Ruby Red / DH 03" twice (any case) is one colour with the counts added; the odd ones are cleaned, not lost
        assertThat(colours).hasSize(4);
        assertThat(colours.get(0).get("name").asText()).isEqualTo("Ruby Red");
        assertThat(colours.get(0).get("hex").asText()).isEqualTo("#9B111E");
        assertThat(colours.get(0).get("code").asText()).isEqualTo("DH 03");
        assertThat(colours.get(0).get("count").asInt()).isEqualTo(3);
        assertThat(colours.get(0).get("colorFamily").asText()).isEqualTo("RED");
        assertThat(colours.get(1).get("name").asText()).isEqualTo("Nude Beige");
        assertThat(colours.get(1).get("hex").asText()).isEqualTo("#C8A28A");
        assertThat(colours.get(2).get("name").asText().length()).isLessThanOrEqualTo(40);
        assertThat(colours.get(2).get("code").asText()).isEqualTo("KE32");
        assertThat(colours.get(3).get("name").asText()).isEqualTo("Mystery");
        assertThat(colours.get(3).get("hex").isNull()).as("a code that is not a colour is dropped, not guessed").isTrue();
        assertThat(out.get("sizes")).hasSize(2); // 50ml and "50 ML" are one
        assertThat(out.get("sizes").get(0).asText()).isEqualTo("50ml");
        assertThat(out.get("note").asText()).contains("white table");
        // the picture itself went to the AI, with the product's name for context only
        assertThat(textRequests).hasSize(1);
        assertThat(textRequests.get(0)).contains("inlineData").contains("image/png").contains("Roniki Gel Pink");
    }

    @Test
    void anOddAnswerNeverBreaksTheScreen() throws Exception {
        textMode = "MESS";
        JsonNode out = readPhoto(png, "image/png", 200);
        assertThat(out.get("colours")).hasSize(1);
        assertThat(out.get("colours").get(0).get("name").asText()).startsWith("Colour");
        assertThat(out.get("colours").get(0).get("count").asInt()).isEqualTo(1);
    }

    @Test
    void whatCannotBeReadIsRefusedInPlainWords() throws Exception {
        assertThat(readPhoto(png, "application/pdf", 422).get("error").asText()).contains("JPEG, PNG or WebP");
        assertThat(readPhoto("", "image/png", 422).get("error").asText()).contains("no picture");
        assertThat(readPhoto("%%% not base64 %%%", "image/png", 422).get("error").asText()).contains("isn't a picture");
        // 6 MB limit
        String big = Base64.getEncoder().encodeToString(new byte[PhotoColourReader.MAX_BYTES + 1]);
        assertThat(readPhoto(big, "image/jpeg", 422).get("error").asText()).contains("too big");
        assertThat(textRequests).as("nothing was sent to the AI").isEmpty();
    }

    @Test
    void aBusyAiIsSaidSoAndTheDataUrlPrefixIsTolerated() throws Exception {
        textMode = "QUOTA";
        assertThat(readPhoto("data:image/png;base64," + png, "image/png", 422).get("error").asText())
                .contains("busy");
    }

    @Test
    void onlyStaffWhoManageProductsCanUseIt() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/admin/variant-jobs/read-photo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"image\":\"" + png + "\",\"mimeType\":\"image/png\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isIn(401, 403);
    }

    // ---------------------------------------------------------------- editing a variant's photo with a prompt

    @Test
    void aPromptEditMakesANewMainPhotoAndKeepsTheOldOneBehindIt() throws Exception {
        Made m = productWithPhoto(true);
        ProductImage before = images.findByVariantIdOrderBySortOrderAsc(m.variantId()).get(0);

        MvcResult r = edit(m, null, "  make the cap\n gold ");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode out = json.readTree(r.getResponse().getContentAsString());
        assertThat(out.get("image").get("isPrimary").asBoolean()).isTrue();
        assertThat(out.get("image").get("source").asText()).isEqualTo("AI_CLEANED");
        assertThat(out.get("previousMainId").asText()).isEqualTo(before.getId().toString());

        List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(m.variantId());
        assertThat(own).hasSize(2);
        assertThat(own.get(0).getId().toString()).isEqualTo(out.get("image").get("id").asText());
        assertThat(own.get(0).isPrimary()).isTrue();
        assertThat(own.get(1).getId()).isEqualTo(before.getId());
        assertThat(own.get(1).isPrimary()).as("the old photo is still there, no longer main").isFalse();
        assertThat(imagePrompts).hasSize(1);
        assertThat(imagePrompts.get(0)).contains("make the cap gold");
        assertThat(uploads.get()).isEqualTo(1);
    }

    @Test
    void anAiMadePhotoCanBeEditedAgainAndAgain() throws Exception {
        Made m = productWithPhoto(true);
        assertThat(edit(m, null, "brighter").getResponse().getStatus()).isEqualTo(200);
        assertThat(edit(m, null, "remove the shadow").getResponse().getStatus()).isEqualTo(200);
        List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(m.variantId());
        assertThat(own).hasSize(3);
        assertThat(own.stream().filter(ProductImage::isPrimary)).hasSize(1);
        assertThat(own.get(0).isPrimary()).isTrue();
    }

    @Test
    void whatCannotBeEditedIsRefusedInPlainWords() throws Exception {
        Made noPhoto = productWithPhoto(false);
        MvcResult r = edit(noPhoto, null, "make it red");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(r.getResponse().getContentAsString()).get("error").asText()).contains("no photo");

        Made m = productWithPhoto(true);
        assertThat(edit(m, null, "   ").getResponse().getStatus()).isEqualTo(422);
        assertThat(edit(m, UUID.randomUUID(), "make it red").getResponse().getStatus()).isEqualTo(422);
        Made other = productWithPhoto(true);
        assertThat(edit(new Made(other.productId(), m.variantId()), null, "make it red").getResponse().getStatus())
                .as("a variant of another product").isEqualTo(404);
        assertThat(imagePrompts).as("the AI was never asked").isEmpty();
    }

    @Test
    void aBusyAiLeavesTheVariantsPhotosAsTheyWere() throws Exception {
        Made m = productWithPhoto(true);
        imageMode = "QUOTA";
        MvcResult r = edit(m, null, "make the cap gold");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(r.getResponse().getContentAsString()).get("error").asText()).contains("busy");
        List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(m.variantId());
        assertThat(own).hasSize(1);
        assertThat(own.get(0).isPrimary()).isTrue();
    }
}
