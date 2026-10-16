package com.nbh.backend.variantjobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductOptionValueRepository;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Variant jobs end to end (VARIANTS_PLAN.md phase 3) through the real poller and worker, against stand-ins for Gemini
 * (photos and text) and Cloudinary. The poller is held off in tests; each test calls {@code pollOnce()} itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VariantJobIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ImageCleanupConfig config;
    @Autowired private IntegrationSettingsService integrations;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ProductImageRepository images;
    @Autowired private ProductOptionValueRepository optionRows;
    @Autowired private VariantJobRepository jobs;
    @Autowired private VariantJobWriter writer;
    @Autowired private VariantJobPoller poller;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private HttpServer stub;
    private final AtomicInteger imageCalls = new AtomicInteger();
    /** Files stored on the Cloudinary stand-in by this test (the job's photos). */
    private final AtomicInteger uploads = new AtomicInteger();
    private final List<String> imagePrompts = Collections.synchronizedList(new ArrayList<>());
    /** What the Gemini photo stand-in does: OK, QUOTA (429 every time) or FAIL_CORAL (400 for prompts naming Coral). */
    private volatile String imageMode = "OK";
    private String originalGeminiUrl;
    private String originalCloudinaryUrl;
    private String token;
    private String png;
    private byte[] pngBytes;

    @BeforeEach
    void setUp() throws Exception {
        // Jobs other runs left unfinished must not be picked up by this test's polls.
        jobs.findAll().stream().filter(j -> j.getStatus().active()).forEach(j -> {
            j.setStatus(VariantJob.Status.CANCELLED);
            jobs.save(j);
        });
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
                imageCalls.incrementAndGet();
                imagePrompts.add(request);
                if ("QUOTA".equals(imageMode)) {
                    status = 429;
                    reply = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}".getBytes(StandardCharsets.UTF_8);
                } else if ("FAIL_CORAL".equals(imageMode) && request.contains("Coral")) {
                    status = 400;
                    reply = "{\"error\":{\"message\":\"refused\"}}".getBytes(StandardCharsets.UTF_8);
                } else {
                    reply = ("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\","
                            + "\"data\":\"" + png + "\"}}]}}]}").getBytes(StandardCharsets.UTF_8);
                }
            } else if (path.contains(":generateContent")) {
                String answer;
                if (request.contains("shade names a cosmetics shop uses")) {
                    answer = "{\"colors\":[{\"index\":0,\"hex\":\"#7A2E8E\"}]}";
                } else {
                    StringBuilder v = new StringBuilder("{\"variants\":[");
                    for (int i = 0; i < 40; i++) {
                        v.append(i == 0 ? "" : ",").append("{\"index\":").append(i)
                                .append(",\"swatchHex\":\"\",\"description\":\"Written for option ").append(i).append(".\"}");
                    }
                    answer = v.append("]}").toString();
                }
                reply = json.writeValueAsBytes(Map.of("candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", answer)))))));
            } else if (path.endsWith("/image/upload")) {
                String id = "j-" + UUID.randomUUID();
                uploads.incrementAndGet();
                reply =("{\"secure_url\":\"https://res.cloudinary.com/test/" + id + ".png\",\"public_id\":\"" + id + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
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

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult r = call(request, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String content = r.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private record Made(String productId, String baseId, String name) {}

    private Made product(boolean withPhoto) throws Exception {
        String name = "Job Lipstick " + UUID.randomUUID().toString().substring(0, 8);
        JsonNode p = ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],"
                + "\"published\":true,\"description\":\"A soft matte lipstick.\","
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}");
        Made made = new Made(p.get("id").asText(), p.get("variants").get(0).get("id").asText(), name);
        if (withPhoto) photo(made.productId(), made.baseId());
        return made;
    }

    private UUID photo(String productId, String variantId) {
        ProductImage photo = new ProductImage();
        photo.setProduct(products.findById(UUID.fromString(productId)).orElseThrow());
        photo.setVariant(variantId == null ? null : variants.findById(UUID.fromString(variantId)).orElseThrow());
        photo.setUrl("http://127.0.0.1:" + stub.getAddress().getPort() + "/photo-" + UUID.randomUUID() + ".png");
        photo.setPrimary(variantId != null);
        photo.setSource(ProductImage.ImageSource.UPLOAD);
        return images.saveAndFlush(photo).getId();
    }

    /** The owner's lipstick, small: two colours, two sizes, step pricing, Gold +150, opening stock on one. */
    private String lipstickRequest(Made p, boolean approveFirst, String extraColor) {
        return "{\"productId\":\"" + p.productId() + "\","
                + "\"colors\":[{\"name\":\"Ruby Red\",\"hex\":\"#9B111E\"},{\"name\":\"Gold Shimmer\",\"hex\":\"#C9A24A\"}"
                + (extraColor == null ? "" : ",{\"name\":\"" + extraColor + "\",\"hex\":\"#F2705E\"}") + "],"
                + "\"sizes\":[{\"name\":\"Mini 2g\"},{\"name\":\"Full 3.5g\"}],"
                + "\"everyCombination\":true,"
                + "\"cells\":[{\"color\":\"Ruby Red\",\"size\":\"Mini 2g\",\"openingStock\":12,\"unitCost\":300}],"
                + "\"pricing\":{\"mode\":\"STEP_UP\",\"start\":650,\"step\":450,"
                + "\"colorAdjustments\":[{\"color\":\"Gold Shimmer\",\"amount\":150}]},"
                + "\"baseOption\":{\"color\":null,\"size\":null},"
                + "\"approveFirst\":" + approveFirst + "}";
    }

    private String start(String body) throws Exception {
        JsonNode job = ok(post("/api/v1/admin/variant-jobs"), body);
        assertThat(job.get("status").asText()).isEqualTo("QUEUED");
        return job.get("id").asText();
    }

    private JsonNode job(String id) throws Exception {
        return ok(get("/api/v1/admin/variant-jobs/" + id), null);
    }

    /** Polls until the job stops working (finished, or paused on the quota). */
    private JsonNode settled(String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            poller.pollOnce();
            JsonNode d = job(id);
            String s = d.get("job").get("status").asText();
            if (!s.equals("QUEUED") && !s.equals("RUNNING")) return d;
            Thread.sleep(200);
        }
        throw new AssertionError("the job never settled: " + job(id));
    }

    private static JsonNode item(JsonNode detail, String label) {
        return StreamSupport.stream(detail.get("items").spliterator(), false)
                .filter(i -> i.get("label").asText().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError(label + " not in " + detail));
    }

    private JsonNode attention() throws Exception {
        return ok(get("/api/v1/admin/attention"), null);
    }

    private static List<JsonNode> attentionFor(JsonNode attention, String jobId) {
        return StreamSupport.stream(attention.get("items").spliterator(), false)
                .filter(i -> jobId.equals(i.path("jobId").asText())).toList();
    }

    // ---------------------------------------------------------------- tests

    private Instant versionOf(Made p) throws Exception {
        return Instant.parse(ok(get("/api/v1/products/" + p.productId()), null).get("updatedAt").asText());
    }

    private java.math.BigDecimal standardCostOf(Made p, String colour, String size) {
        return jdbc.queryForObject("SELECT standard_cost FROM product_variant WHERE product_id = ?::uuid "
                + "AND option_color = ? AND option_size = ? AND deleted = false", java.math.BigDecimal.class,
                p.productId(), colour, size);
    }

    private java.math.BigDecimal averageCostOf(Made p, String colour, String size) {
        return jdbc.queryForObject("SELECT b.avg_cost FROM stock_balance b JOIN product_variant v ON v.id = b.variant_id "
                + "WHERE v.product_id = ?::uuid AND v.option_color = ? AND v.option_size = ? AND b.account = 'SHOP'",
                java.math.BigDecimal.class, p.productId(), colour, size);
    }

    @Test
    void descriptionsAJobWritesMoveTheProductVersionSoAnOlderEditorFormIsNotPutBack() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, false, null));
        JsonNode done = settled(id);
        String itemId = done.get("items").get(0).get("id").asText();
        jdbc.update("UPDATE product SET updated_at = now() - interval '1 hour' WHERE id = ?::uuid", p.productId());

        writer.saveDescriptions(UUID.fromString(id), Map.of(UUID.fromString(itemId), "Rewritten for the shop."));

        assertThat(versionOf(p)).as("the version an open editor compares against moved")
                .isAfter(Instant.now().minusSeconds(120));
    }

    @Test
    void publishingAnApproveFirstJobMovesTheProductVersion() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, true, null));
        assertThat(settled(id).get("job").get("status").asText()).isEqualTo("AWAITING_APPROVAL");
        jdbc.update("UPDATE product SET updated_at = now() - interval '1 hour' WHERE id = ?::uuid", p.productId());

        ok(post("/api/v1/admin/variant-jobs/" + id + "/publish"), null);

        assertThat(versionOf(p)).isAfter(Instant.now().minusSeconds(120));
    }

    @Test
    void openingStockWithoutACostIsNotValuedAtAnotherSizesCost() throws Exception {
        Made p = product(true);
        jdbc.update("UPDATE product_variant SET standard_cost = 300 WHERE id = ?::uuid", p.baseId());
        String body = "{\"productId\":\"" + p.productId() + "\","
                + "\"colors\":[{\"name\":\"Ruby Red\",\"hex\":\"#9B111E\"},{\"name\":\"Gold Shimmer\",\"hex\":\"#C9A24A\"}],"
                + "\"sizes\":[{\"name\":\"Mini 2g\"},{\"name\":\"Full 3.5g\"}],"
                + "\"everyCombination\":true,"
                + "\"cells\":[{\"color\":\"Gold Shimmer\",\"size\":\"Mini 2g\",\"openingStock\":5},"
                + "{\"color\":\"Ruby Red\",\"size\":\"Full 3.5g\",\"openingStock\":5}],"
                + "\"pricing\":{\"mode\":\"SAME\",\"price\":1000},"
                + "\"baseOption\":{\"color\":\"Ruby Red\",\"size\":\"Mini 2g\"},\"approveFirst\":false}";
        assertThat(settled(start(body)).get("job").get("status").asText()).isEqualTo("COMPLETED");

        // A colour of the product's own size costs what the product costs...
        assertThat(standardCostOf(p, "Gold Shimmer", "Mini 2g")).isEqualByComparingTo("300");
        assertThat(averageCostOf(p, "Gold Shimmer", "Mini 2g")).isEqualByComparingTo("300");
        // ...a new size's cost is not known, so it is neither given the other size's cost nor hidden: valued at 0.
        assertThat(standardCostOf(p, "Ruby Red", "Full 3.5g")).isNull();
        assertThat(averageCostOf(p, "Ruby Red", "Full 3.5g")).isEqualByComparingTo("0");
    }

    @Test
    void aCancelPressedWhileAWorkerHoldsAnOlderCopyOfTheJobIsNotWrittenBackOver() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, false, null));
        UUID jobId = UUID.fromString(id);
        var worker = new org.springframework.transaction.support.TransactionTemplate(txManager);
        var staff = new org.springframework.transaction.support.TransactionTemplate(txManager);
        staff.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        worker.executeWithoutResult(s -> {
            VariantJob mine = jobs.findById(jobId).orElseThrow(); // the worker's copy: not cancelled
            staff.executeWithoutResult(t -> jdbc.update(
                    "UPDATE variant_job SET cancel_requested = true WHERE id = ?::uuid", id)); // Cancel, committed
            mine.setHeartbeatAt(Instant.now()); // the worker's later save of the job
            jobs.save(mine);
        });

        assertThat(jdbc.queryForObject("SELECT cancel_requested FROM variant_job WHERE id = ?::uuid", Boolean.class, id))
                .as("the worker's save must not write the old 'not cancelled' back").isTrue();
    }

    @Test
    void aLateAttachForAnItemAnotherRunAlreadyFinishedAddsNoSecondPhoto() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, false, null));
        JsonNode first = settled(id).get("items").get(0);
        UUID itemId = UUID.fromString(first.get("id").asText());
        String variantId = first.get("variantId").asText();
        Integer before = jdbc.queryForObject("SELECT count(*) FROM product_image WHERE variant_id = ?::uuid",
                Integer.class, variantId);

        writer.attachPhoto(UUID.fromString(id), itemId, "https://res.cloudinary.com/test/late-worker.png", true);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_image WHERE variant_id = ?::uuid",
                Integer.class, variantId)).isEqualTo(before).isEqualTo(1);
    }

    @Test
    void aWholeJobMakesPricedNamedDescribedPhotographedVariantsWithStockAndOneRecolourPerColour() throws Exception {
        Made p = product(true);
        String openedAt = ok(get("/api/v1/products/" + p.productId()), null).get("updatedAt").asText();
        Thread.sleep(1100); // the editor compares to the second
        String id = start(lipstickRequest(p, false, null));
        JsonNode done = settled(id);
        assertThat(ok(get("/api/v1/products/" + p.productId()), null).get("updatedAt").asText())
                .as("an editor opened before the job must reload, not save its older variant list")
                .isNotEqualTo(openedAt);

        JsonNode j = done.get("job");
        assertThat(j.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(j.get("variantsCreated").asInt()).isEqualTo(4);
        assertThat(j.get("descriptionsDone").asInt()).isEqualTo(4);
        assertThat(j.get("photosDone").asInt()).isEqualTo(4);
        assertThat(j.get("createdByName").asText()).isNotBlank();
        assertThat(StreamSupport.stream(j.get("summary").spliterator(), false).map(JsonNode::asText).toList())
                .containsExactly("Prices: going up with size, KSh 650 → KSh 1,100", "Gold Shimmer: +KSh 150 in every size");
        assertThat(imageCalls.get()).as("one recolour per colour, the sizes share it").isEqualTo(2);

        JsonNode goldFull = item(done, "Gold Shimmer / Full 3.5g");
        assertThat(goldFull.get("price").asDouble()).isEqualTo(1250.0);
        assertThat(goldFull.get("live").asBoolean()).isTrue();
        assertThat(goldFull.get("imageUrl").asText()).startsWith("https://res.cloudinary.com/test/");
        assertThat(goldFull.get("swatchHex").asText()).isEqualTo("#C9A24A");
        assertThat(item(done, "Gold Shimmer / Mini 2g").get("imageUrl").asText())
                .as("the sizes of one colour share its photo").isEqualTo(goldFull.get("imageUrl").asText());

        JsonNode product = ok(get("/api/v1/products/" + p.productId()), null);
        JsonNode rubyMini = StreamSupport.stream(product.get("variants").spliterator(), false)
                .filter(v -> "Ruby Red / Mini 2g".equals(v.path("optionLabel").asText())).findFirst().orElseThrow();
        assertThat(rubyMini.get("variantName").asText()).isEqualTo(p.name() + " – Ruby Red / Mini 2g");
        assertThat(rubyMini.get("description").asText()).startsWith("Written for option");
        assertThat(rubyMini.get("availableQty").asDouble()).as("opening stock").isEqualTo(12.0);
        assertThat(rubyMini.get("sellingPrice").asDouble()).isEqualTo(650.0);
        assertThat(optionRows.findByProductIdOrderByKindAscPositionAsc(UUID.fromString(p.productId())).stream()
                .map(o -> o.getKind() + ":" + o.getValue() + ":" + o.getSwatchHex()).toList())
                .containsExactly("COLOR:Ruby Red:#9B111E", "COLOR:Gold Shimmer:#C9A24A", "SIZE:Mini 2g:null", "SIZE:Full 3.5g:null");

        // The three it made without stock show in Needs attention until stock is added.
        List<JsonNode> needs = attentionFor(attention(), id);
        assertThat(needs).singleElement().satisfies(n -> {
            assertThat(n.get("title").asText()).contains("3 new variants have no stock");
            assertThat(n.get("actions").get(0).get("kind").asText()).isEqualTo("ADD_STOCK");
        });
        for (JsonNode vid : needs.get(0).get("variantIds")) {
            ok(put("/api/v1/products/" + p.productId() + "/variants/" + vid.asText() + "/stock"),
                    "{\"receivedQty\":\"5\",\"allowUnknownCost\":true}");
        }
        assertThat(attentionFor(attention(), id)).isEmpty();
    }

    @Test
    void labelTextChangesThePrintedSizeOnEachVariantAndASizePhotoIsWhatThatSizeIsMadeFrom() throws Exception {
        Made p = product(true);
        UUID bigPhoto = photo(p.productId(), null);
        String id = start("{\"productId\":\"" + p.productId() + "\",\"colors\":[{\"name\":\"Ruby Red\"}],"
                + "\"sizes\":[{\"name\":\"Mini 2g\",\"labelText\":\"2 g\"},{\"name\":\"Full 3.5g\",\"photoImageId\":\"" + bigPhoto + "\"}],"
                + "\"everyCombination\":true,\"pricing\":{\"mode\":\"SAME\",\"price\":900}}");
        JsonNode done = settled(id);

        assertThat(done.get("job").get("status").asText()).isEqualTo("COMPLETED");
        // Mini: recolour of the product photo + a label edit. Full: recolour of the size's own photo, no label.
        assertThat(imageCalls.get()).isEqualTo(3);
        assertThat(imagePrompts).anyMatch(prompt -> prompt.contains("change that text to exactly \\\"2 g\\\""));
        assertThat(optionRows.findByProductIdOrderByKindAscPositionAsc(UUID.fromString(p.productId())).stream()
                .filter(o -> o.getValue().equals("Mini 2g")).findFirst().orElseThrow().getSizeLabelText()).isEqualTo("2 g");
    }

    /** Photo rows of the variants a settled job made: how many each has, in item order. */
    private List<Integer> photoRowsPerVariant(JsonNode done) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode i : done.get("items")) {
            out.add(jdbc.queryForObject("SELECT count(*) FROM product_image WHERE variant_id = ?::uuid",
                    Integer.class, i.get("variantId").asText()));
        }
        return out;
    }

    @Test
    void everyVariantAJobMakesEndsWithExactlyOnePhotoAndNoStoredFileIsLeftOver() throws Exception {
        // Two colours x two sizes, no label text: one master per colour, shared, so two files for four variants.
        Made p = product(true);
        int before = uploads.get();
        JsonNode plain = settled(start(lipstickRequest(p, false, null)));
        assertThat(plain.get("items")).hasSize(4);
        assertThat(photoRowsPerVariant(plain)).as("one photo row per variant").containsOnly(1);
        assertThat(uploads.get() - before).as("one stored file per colour, shared by its sizes").isEqualTo(2);

        // One colour, a size with label text and a size with its own photo: the label edit stores ITS file and the
        // colour master is not stored at all, so the two variants have two files between them (it used to be three).
        Made q = product(true);
        UUID bigPhoto = photo(q.productId(), null);
        int beforeLabel = uploads.get();
        JsonNode labelled = settled(start("{\"productId\":\"" + q.productId() + "\",\"colors\":[{\"name\":\"Ruby Red\"}],"
                + "\"sizes\":[{\"name\":\"Mini 2g\",\"labelText\":\"2 g\"},{\"name\":\"Full 3.5g\",\"photoImageId\":\""
                + bigPhoto + "\"}],\"everyCombination\":true,\"pricing\":{\"mode\":\"SAME\",\"price\":900}}"));
        assertThat(labelled.get("items")).hasSize(2);
        assertThat(photoRowsPerVariant(labelled)).containsOnly(1);
        assertThat(uploads.get() - beforeLabel).as("a stored file for each variant's photo and nothing else").isEqualTo(2);
    }

    @Test
    void aRequestWithMistakesIsPreviewedWithItsErrorsAndCannotStart() throws Exception {
        Made p = product(true);
        String bad = "{\"productId\":\"" + p.productId() + "\",\"colors\":[{\"name\":\"Red\"},{\"name\":\"red\"}],"
                + "\"pricing\":{\"mode\":\"SAME\"}}";
        JsonNode preview = ok(post("/api/v1/admin/variant-jobs/preview"), bad);
        assertThat(preview.get("errors").toString()).contains("listed twice", "Give the price for all of them");

        MvcResult refused = call(post("/api/v1/admin/variant-jobs"), bad);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("listed twice");
        assertThat(jobs.findByProductIdOrderByCreatedAtDesc(UUID.fromString(p.productId()))).isEmpty();

        JsonNode good = ok(post("/api/v1/admin/variant-jobs/preview"), lipstickRequest(p, false, null));
        assertThat(good.get("errors")).isEmpty();
        assertThat(good.get("estimate").get("variants").asInt()).isEqualTo(4);
        assertThat(good.get("estimate").get("photoCalls").asInt()).isEqualTo(2);
        assertThat(good.get("warnings").toString()).contains("won't show in the colour and size picker");
    }

    @Test
    void approveFirstKeepsTheVariantsHiddenUntilPublishedAndSaysSoInNeedsAttention() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, true, null));
        JsonNode done = settled(id);

        assertThat(done.get("job").get("status").asText()).isEqualTo("AWAITING_APPROVAL");
        assertThat(item(done, "Ruby Red / Full 3.5g").get("live").asBoolean()).isFalse();
        assertThat(attentionFor(attention(), id)).anySatisfy(n ->
                assertThat(n.get("actions").get(0).get("kind").asText()).isEqualTo("PUBLISH_JOB"));

        JsonNode published = ok(post("/api/v1/admin/variant-jobs/" + id + "/publish"), null);
        assertThat(published.get("job").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(item(published, "Ruby Red / Full 3.5g").get("live").asBoolean()).isTrue();
        assertThat(attentionFor(attention(), id)).noneMatch(n -> n.get("actions").get(0).get("kind").asText().equals("PUBLISH_JOB"));
    }

    @Test
    void theProductsOwnVariantTakesItsColourAndSizeAndLosesAPlaceholderName() throws Exception {
        Made p = product(true);
        String id = start("{\"productId\":\"" + p.productId() + "\",\"colors\":[{\"name\":\"White\"},{\"name\":\"Black\"}],"
                + "\"everyCombination\":true,\"pricing\":{\"mode\":\"SAME\",\"price\":2600},"
                + "\"baseOption\":{\"color\":\"white\",\"size\":null}}");
        JsonNode done = settled(id);

        assertThat(done.get("items")).as("White is the product itself, only Black is made").hasSize(1);
        var base = variants.findById(UUID.fromString(p.baseId())).orElseThrow();
        assertThat(base.getOptionColor()).isEqualTo("White");
        assertThat(base.getVariantName()).isEqualTo(p.name() + " – White");
        assertThat(base.getOptionLabel()).isEqualTo("White");
    }

    @Test
    void twoJobsRunSideBySide() throws Exception {
        Made a = product(true);
        Made b = product(true);
        String first = start(lipstickRequest(a, false, null));
        String second = start(lipstickRequest(b, false, null));

        assertThat(poller.pollOnce()).as("both taken at once").isEqualTo(2);
        assertThat(settled(first).get("job").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(settled(second).get("job").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void theQuotaPausesAJobWhichCarriesOnByItselfWithoutMakingAnythingTwice() throws Exception {
        imageMode = "QUOTA";
        Made p = product(true);
        String id = start(lipstickRequest(p, false, null));
        JsonNode paused = settled(id);
        assertThat(paused.get("job").get("status").asText()).isEqualTo("PAUSED_QUOTA");
        assertThat(paused.get("job").get("variantsCreated").asInt()).isEqualTo(4);
        JsonNode needs = attention();
        assertThat(attentionFor(needs, id)).singleElement().satisfies(n -> {
            assertThat(n.get("severity").asText()).isEqualTo("INFO");
            assertThat(n.get("title").asText()).contains("AI quota reached");
        });

        imageMode = "OK";
        assertThat(poller.pollOnce()).as("not before its time").isZero();
        VariantJob job = jobs.findById(UUID.fromString(id)).orElseThrow();
        job.setNextAttemptAt(Instant.now().minusSeconds(1));
        jobs.save(job);

        JsonNode done = settled(id);
        assertThat(done.get("job").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("job").get("photosDone").asInt()).isEqualTo(4);
        assertThat(variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(UUID.fromString(p.productId()))).hasSize(5);
    }

    @Test
    void aJobWhoseServerStoppedMidwayIsTakenOverAndFinished() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, false, null));
        UUID jobId = UUID.fromString(id);
        // As if a worker made the variants and then the server went down: RUNNING, heartbeat long gone.
        writer.claim("crashed-worker", Instant.now().minusSeconds(3600));
        writer.createAll(jobId);
        VariantJob job = jobs.findById(jobId).orElseThrow();
        job.setHeartbeatAt(Instant.now().minusSeconds(3600));
        jobs.save(job);

        JsonNode done = settled(id);
        assertThat(done.get("job").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(UUID.fromString(p.productId())))
                .as("the base and the four, made once").hasSize(5);
    }

    @Test
    void aFailedPhotoIsListedInNeedsAttentionAndARetryFixesIt() throws Exception {
        imageMode = "FAIL_CORAL";
        Made p = product(true);
        String id = start(lipstickRequest(p, false, "Coral Kiss"));
        JsonNode done = settled(id);

        assertThat(done.get("job").get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(done.get("job").get("photosFailed").asInt()).isEqualTo(2);
        assertThat(item(done, "Coral Kiss / Mini 2g").get("photoStatus").asText()).isEqualTo("FAILED");
        assertThat(item(done, "Coral Kiss / Mini 2g").get("live").asBoolean()).as("still on sale").isTrue();
        assertThat(imageCalls.get()).as("a failed colour is not asked for again for its other size").isEqualTo(3);
        JsonNode photosItem = attentionFor(attention(), id).stream()
                .filter(n -> n.get("title").asText().contains("photos failed")).findFirst().orElseThrow();
        assertThat(photosItem.get("detail").asText()).contains("Coral Kiss / Mini 2g", "Coral Kiss / Full 3.5g");
        assertThat(photosItem.get("variantIds")).hasSize(2);

        imageMode = "OK";
        JsonNode retried = ok(post("/api/v1/admin/variant-jobs/" + id + "/retry"), null);
        assertThat(retried.get("job").get("status").asText()).isEqualTo("QUEUED");
        JsonNode fixed = settled(id);
        assertThat(fixed.get("job").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(fixed.get("job").get("photosFailed").asInt()).isZero();
        assertThat(attentionFor(attention(), id)).noneMatch(n -> n.get("title").asText().contains("photos failed"));
    }

    @Test
    void aFailedPhotoStopsBeingListedOnceStaffGiveTheVariantAPhotoOfItsOwn() throws Exception {
        imageMode = "FAIL_CORAL";
        Made p = product(true);
        String id = start(lipstickRequest(p, false, "Coral Kiss"));
        JsonNode done = settled(id);
        String mini = item(done, "Coral Kiss / Mini 2g").get("variantId").asText();
        String full = item(done, "Coral Kiss / Full 3.5g").get("variantId").asText();
        assertThat(attentionFor(attention(), id)).anyMatch(n -> n.get("title").asText().contains("2 photos failed"));

        photo(p.productId(), mini); // staff upload one, the way the item's own button suggests
        assertThat(attentionFor(attention(), id)).anyMatch(n -> n.get("title").asText().contains("1 photo failed"));

        photo(p.productId(), full);
        assertThat(attentionFor(attention(), id)).noneMatch(n -> n.get("title").asText().contains("photo"));
    }

    @Test
    void dismissingAFinishedJobSetsItsFailuresAsideWithoutAnyAiCall() throws Exception {
        imageMode = "FAIL_CORAL";
        Made p = product(true);
        String id = start(lipstickRequest(p, false, "Coral Kiss"));
        assertThat(settled(id).get("job").get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        int calls = imageCalls.get();

        JsonNode dismissed = ok(post("/api/v1/admin/variant-jobs/" + id + "/dismiss"), null);

        assertThat(dismissed.get("job").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(dismissed.get("job").get("photosFailed").asInt()).isZero();
        assertThat(item(dismissed, "Coral Kiss / Mini 2g").get("photoStatus").asText()).isEqualTo("SKIPPED");
        assertThat(attentionFor(attention(), id)).noneMatch(n -> n.get("title").asText().contains("photo"));
        assertThat(imageCalls.get()).as("dismissing never asks the AI for anything").isEqualTo(calls);

        // A job that is still waiting cannot be dismissed.
        String waiting = start(lipstickRequest(product(true), false, null));
        assertThat(call(post("/api/v1/admin/variant-jobs/" + waiting + "/dismiss"), null).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void aJobWaitingForApprovalStaysInNeedsAttentionBehindFiftyNewerJobs() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, true, null));
        assertThat(settled(id).get("job").get("status").asText()).isEqualTo("AWAITING_APPROVAL");
        try {
            jdbc.update("INSERT INTO variant_job (product_id, status, request, summary, created_at) "
                    + "SELECT ?::uuid, 'CANCELLED', '{}', 'attention-test-filler', now() FROM generate_series(1, 55)",
                    p.productId());
            assertThat(attentionFor(attention(), id))
                    .as("55 newer jobs must not push an unresolved approval off the list")
                    .anyMatch(n -> n.get("title").asText().contains("waiting for your approval"));
        } finally {
            jdbc.update("DELETE FROM variant_job WHERE summary = 'attention-test-filler'");
        }
    }

    @Test
    void theOldNeedsImagesListDoesNotOfferAJobsVariantsForASecondPhoto() throws Exception {
        imageMode = "FAIL_CORAL";
        Made p = product(true);
        String id = start(lipstickRequest(p, false, "Coral Kiss"));
        JsonNode done = settled(id);
        String failedVariant = item(done, "Coral Kiss / Mini 2g").get("variantId").asText();

        String list = ok(get("/api/v1/admin/variant-generation/needs-images"), null).toString();

        assertThat(list).as("a job's own variants report through Needs attention, not the old panel")
                .doesNotContain(failedVariant);
    }

    @Test
    void aWaitingJobCanBeCancelledAndAFinishedOneCannot() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, false, null));
        JsonNode cancelled = ok(post("/api/v1/admin/variant-jobs/" + id + "/cancel"), null);
        assertThat(cancelled.get("job").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(poller.pollOnce()).isZero();
        assertThat(call(post("/api/v1/admin/variant-jobs/" + id + "/cancel"), null).getResponse().getStatus()).isEqualTo(409);
        assertThat(call(post("/api/v1/admin/variant-jobs/" + id + "/retry"), null).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aCombinationTheProductGainedAfterThePreviewIsSkippedNotDoubled() throws Exception {
        Made p = product(true);
        String id = start(lipstickRequest(p, false, null));
        // Meanwhile someone adds Ruby Red / Mini 2g in the editor.
        JsonNode product = ok(get("/api/v1/products/" + p.productId()), null);
        ok(put("/api/v1/products/" + p.productId() + "/full"), "{\"name\":\"" + p.name() + "\",\"categoryIds\":[],"
                + "\"published\":true,\"variants\":[{\"id\":\"" + p.baseId() + "\",\"variantName\":\"Default\","
                + "\"sellingPrice\":\"1000\",\"isActive\":true},{\"variantName\":\"By hand\",\"sellingPrice\":\"600\","
                + "\"isActive\":true,\"options\":{\"color\":\"ruby red\",\"size\":\"Mini 2g\"}}]}");
        assertThat(product).isNotNull();

        JsonNode done = settled(id);
        assertThat(item(done, "Ruby Red / Mini 2g").get("photoStatus").asText()).isEqualTo("SKIPPED");
        assertThat(item(done, "Ruby Red / Mini 2g").get("variantId").isNull()).isTrue();
        assertThat(variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(UUID.fromString(p.productId()))).hasSize(5);
    }

    /**
     * 2026-10-16: the wizard now tidies colour names ("candy Colors 17 candy Colors 17" is sent as "Candy Colors 17").
     * A product whose colour was saved before that, the messy way, must still count as already having it.
     */
    @Test
    void aColourSavedBeforeNamesWereTidiedIsStillRecognisedAndNotMadeTwice() throws Exception {
        Made p = product(true);
        ok(put("/api/v1/products/" + p.productId() + "/full"), "{\"name\":\"" + p.name() + "\",\"categoryIds\":[],"
                + "\"published\":true,\"variants\":[{\"id\":\"" + p.baseId() + "\",\"variantName\":\"Default\","
                + "\"sellingPrice\":\"1000\",\"isActive\":true},{\"variantName\":\"Candy\",\"sellingPrice\":\"600\","
                + "\"isActive\":true,\"options\":{\"color\":\"candy Colors 17 candy Colors 17\",\"size\":null}}]}");

        JsonNode plan = ok(post("/api/v1/admin/variant-jobs/preview"), "{\"productId\":\"" + p.productId() + "\","
                + "\"colors\":[{\"name\":\"Candy Colors 17\",\"hex\":\"#B9E1EF\"},{\"name\":\"Hot Pink PU30\",\"hex\":\"#CF4D88\"}],"
                + "\"sizes\":[],\"everyCombination\":true,\"pricing\":{\"mode\":\"SAME\",\"price\":600},"
                + "\"baseOption\":{\"color\":null,\"size\":null},\"approveFirst\":false}");

        assertThat(plan.toString()).contains("Already on this product, will be skipped: Candy Colors 17");
    }

    @Test
    void colourNamesGetDotsFromTheListAndTheRestFromTheAi() throws Exception {
        JsonNode s = ok(post("/api/v1/admin/variant-jobs/suggest-swatches"),
                "{\"names\":[\"Deep Ruby Red\",\"Gold Shimmer\",\"Unicorn Dream\",\"Clear Top Coat\"]}");
        assertThat(s.get(0).get("hex").asText()).isEqualTo("#9B111E");
        assertThat(s.get(0).get("source").asText()).isEqualTo("LIST");
        assertThat(s.get(0).get("colorFamily").asText()).isEqualTo("RED");
        assertThat(s.get(1).get("colorFamily").asText()).isEqualTo("GOLD");
        assertThat(s.get(2).get("source").asText()).isEqualTo("AI");
        assertThat(s.get(2).get("hex").asText()).isEqualTo("#7A2E8E");
        assertThat(s.get(3).get("colorFamily").asText()).isEqualTo("CLEAR");
    }

    // ---------------------------------------------------------------- photo instructions, trial, redo (2026-10-15)

    private String stubUrl(String name) {
        return "http://127.0.0.1:" + stub.getAddress().getPort() + "/" + name + ".png";
    }

    /** The photo-edit requests the AI was sent that make this shade. */
    private List<String> promptsFor(String shade) {
        synchronized (imagePrompts) {
            return imagePrompts.stream().filter(r -> r.contains("shade is \\\"" + shade + "\\\"")).toList();
        }
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }

    @Test
    void onlyThePartShowingTheShadeChangesAndStaffsWordsAndAGroupsOwnWordsAndPhotoReachTheAi() throws Exception {
        Made p = product(true);
        String body = lipstickRequest(p, false, null);
        body = body.substring(0, body.length() - 1)
                + ",\"photoGuide\":{\"note\":\"Only the round top of the cap shows the colour; the bottle stays white.\"},"
                + "\"photoGroups\":[{\"colors\":[\"gold shimmer\"],\"note\":\"Black bottle, white cap.\","
                + "\"referenceUrl\":\"" + stubUrl("real-black-bottle") + "\"}]}";
        settled(start(body));

        String ruby = promptsFor("Ruby Red").get(0);
        assertThat(ruby).contains("Change ONLY the part that shows the shade")
                .contains("Only the round top of the cap shows the colour").doesNotContain("Black bottle")
                .doesNotContain("every part that shares its main colour");
        assertThat(count(ruby, "inlineData")).as("the product's photo alone").isEqualTo(1);
        String gold = promptsFor("Gold Shimmer").get(0);
        assertThat(gold).contains("Only the round top of the cap").contains("Black bottle, white cap.")
                .contains("A SECOND photo is attached");
        assertThat(count(gold, "inlineData")).as("the product's photo and the group's real photo").isEqualTo(2);
    }

    @Test
    void aPhotoTriedAndApprovedBeforeGenerateIsThatColoursPhotoWithoutAnotherAiCall() throws Exception {
        Made p = product(true);
        JsonNode trial = ok(post("/api/v1/admin/variant-jobs/photo-trial"), "{\"productId\":\"" + p.productId() + "\","
                + "\"colors\":[{\"name\":\"Ruby Red\",\"hex\":\"#9B111E\"}],"
                + "\"photoGuide\":{\"note\":\"Only the top of the cap shows the colour.\"}}");
        assertThat(trial.get("original").asText()).startsWith("http://127.0.0.1:");
        String approved = trial.get("photos").get(0).get("url").asText();
        assertThat(approved).startsWith("https://res.cloudinary.com/test/");
        assertThat(promptsFor("Ruby Red")).singleElement().asString().contains("Only the top of the cap");

        String body = lipstickRequest(p, false, null);
        body = body.substring(0, body.length() - 1)
                + ",\"photoGuide\":{\"note\":\"Only the top of the cap shows the colour.\"},"
                + "\"trialPhotos\":[{\"color\":\"Ruby Red\",\"url\":\"" + approved + "\"}]}";
        JsonNode done = settled(start(body));
        assertThat(done.get("job").get("photosDone").asInt()).isEqualTo(4);
        assertThat(item(done, "Ruby Red / Mini 2g").get("imageUrl").asText()).isEqualTo(approved);
        assertThat(item(done, "Ruby Red / Full 3.5g").get("imageUrl").asText()).isEqualTo(approved);
        assertThat(promptsFor("Ruby Red")).as("the approved photo was not made again").hasSize(1);
        assertThat(promptsFor("Gold Shimmer")).hasSize(1);
    }

    @Test
    void redoPhotosReplacesTheWrongAiPhotosKeepsStaffsOwnAndWritesNoDescriptions() throws Exception {
        Made p = product(true);
        settled(start(lipstickRequest(p, false, null)));
        JsonNode variantList = ok(get("/api/v1/products/" + p.productId()), null).get("variants");
        List<String> ids = StreamSupport.stream(variantList.spliterator(), false).map(v -> v.get("id").asText()).toList();
        String rubyMini = StreamSupport.stream(variantList.spliterator(), false)
                .filter(v -> "Ruby Red / Mini 2g".equals(v.path("optionLabel").asText()))
                .findFirst().orElseThrow().get("id").asText();
        String wrong = images.findByVariantIdOrderBySortOrderAsc(UUID.fromString(rubyMini)).get(0).getUrl();
        UUID staffsOwn = photo(p.productId(), rubyMini);
        int before = imagePrompts.size();

        String list = String.join(",", ids.stream().map(i -> "\"" + i + "\"").toList());
        JsonNode job = ok(post("/api/v1/admin/variant-jobs/redo-photos"), "{\"productId\":\"" + p.productId() + "\","
                + "\"baseVariantId\":\"" + p.baseId() + "\",\"variantIds\":[" + list + "],"
                + "\"photoGuide\":{\"note\":\"Only the top of the cap shows the colour.\"}}");
        assertThat(job.get("variantsTotal").asInt()).as("the variant the photos are made from is left out").isEqualTo(4);
        JsonNode done = settled(job.get("id").asText());
        assertThat(done.get("job").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("job").get("photosDone").asInt()).isEqualTo(4);
        assertThat(done.get("items").get(0).get("descriptionStatus").asText()).isEqualTo("SKIPPED");
        assertThat(imagePrompts.size() - before).as("one new photo per colour, shared by its sizes").isEqualTo(2);
        assertThat(imagePrompts.get(before)).contains("Only the top of the cap shows the colour");

        List<ProductImage> now = images.findByVariantIdOrderBySortOrderAsc(UUID.fromString(rubyMini));
        assertThat(now).extracting(ProductImage::getUrl).doesNotContain(wrong);
        assertThat(now.get(0).isPrimary()).isTrue();
        assertThat(now.get(0).getSource()).isEqualTo(ProductImage.ImageSource.AI_CLEANED);
        assertThat(now).as("the new photo first, staff's own upload kept behind it").hasSize(2);
        assertThat(now.get(1).getId()).isEqualTo(staffsOwn);
        assertThat(images.findByVariantIdOrderBySortOrderAsc(UUID.fromString(p.baseId())))
                .as("the photo the others are made from is untouched").hasSize(1);
    }

    @Test
    void instructionsNamingAPhotoThatIsNotTheShopsOwnAreRefused() throws Exception {
        Made p = product(true);
        String body = lipstickRequest(p, false, null);
        body = body.substring(0, body.length() - 1)
                + ",\"photoGuide\":{\"note\":\"x\",\"referenceUrl\":\"https://elsewhere.example/a.png\"}}";
        MvcResult r = call(post("/api/v1/admin/variant-jobs"), body);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getContentAsString()).contains("one of the shop");
        MvcResult t = call(post("/api/v1/admin/variant-jobs/photo-trial"), "{\"productId\":\"" + p.productId() + "\","
                + "\"colors\":[{\"name\":\"Ruby Red\"},{\"name\":\"Nude\"},{\"name\":\"Gold\"}]}");
        assertThat(t.getResponse().getStatus()).as("two at a time at most").isEqualTo(422);
    }
}
