package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The AI photo studio end to end (owner, 2026-10-07): photos made in "preview first" mode wait as drafts that the shop
 * never shows, staff approve / redo / reject them, "go live now" publishes at once, and deleting anything also deletes
 * it on Cloudinary. Gemini and Cloudinary are stand-ins; everything else is the real application and database.
 */
class PhotoStudioIntegrationTest extends PhotoStudioTestContext {

    private static final String CLOUD = "https://res.cloudinary.com/demo/image/upload/v1/";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductImageRepository images;
    @Autowired private ImageDraftService draftService;
    @Autowired private JdbcTemplate jdbc;

    private String token;
    private final AtomicInteger uploads = new AtomicInteger();

    @BeforeEach
    void signInAndStandInForGeminiAndCloudinary() throws Exception {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);

        reset(gemini, cloudinary);
        when(cloudinary.configured()).thenReturn(true);
        when(cloudinary.cloudName()).thenReturn("demo");
        when(gemini.download(anyString())).thenReturn(new GeminiImageClient.Image(png(), "image/png"));
        when(gemini.edit(any(), anyString())).thenAnswer(inv -> new GeminiImageClient.Image(png(), "image/png"));
        when(cloudinary.upload(any(), anyString(), anyString())).thenAnswer(inv -> {
            String publicId = inv.getArgument(2);
            uploads.incrementAndGet();
            return new CloudinaryUploader.Uploaded(CLOUD + "products/" + publicId + ".png", "products/" + publicId);
        });
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode staff(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private JsonNode shopper(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A published product with two options, each with one real photo. */
    private JsonNode twoOptionProduct() throws Exception {
        JsonNode product = staff(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Studio Gel")
                + "\",\"categoryIds\":[],\"published\":true,\"variants\":["
                + "{\"variantName\":\"Red\",\"sellingPrice\":\"400\",\"isActive\":true},"
                + "{\"variantName\":\"Blue\",\"sellingPrice\":\"420\",\"isActive\":true}]}");
        for (int i = 0; i < 2; i++) {
            staff(post("/api/v1/products/" + product.get("id").asText() + "/images"), "{\"url\":\"https://photos.test/"
                    + (i == 0 ? "red" : "blue") + "-original-" + UUID.randomUUID().toString().substring(0, 6)
                    + ".jpg\",\"isPrimary\":false,\"variantId\":\"" + product.get("variants").get(i).get("id").asText() + "\"}");
        }
        return product;
    }

    private String optionId(JsonNode product, int index) {
        return product.get("variants").get(index).get("id").asText();
    }

    private String optionRunBody(String views, String note, boolean publishNow) {
        return "{\"views\":" + views + (note == null ? "" : ",\"note\":\"" + note + "\"") + ",\"publishNow\":" + publishNow + "}";
    }

    private JsonNode runOnOption(String variantId, String body) throws Exception {
        JsonNode batch = staff(post("/api/v1/admin/image-cleanup/options/" + variantId + "/run"), body);
        UUID id = UUID.fromString(batch.get("id").asText());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(
                () -> !"IN_PROGRESS".equals(staff(get("/api/v1/admin/image-cleanup/batches/" + id), null).get("status").asText()));
        return staff(get("/api/v1/admin/image-cleanup/batches/" + id), null);
    }

    private List<String> shopPhotos(String variantId) throws Exception {
        List<String> urls = new ArrayList<>();
        shopper(get("/api/v1/catalog/variants/" + variantId)).get("images").forEach(i -> urls.add(i.get("url").asText()));
        return urls;
    }

    private List<JsonNode> drafts(String productId) throws Exception {
        List<JsonNode> list = new ArrayList<>();
        staff(get("/api/v1/admin/image-cleanup/drafts").param("productId", productId), null).forEach(list::add);
        return list;
    }

    // ---- preview first (the default) ----

    @Test
    void aRunWaitsAsDraftsNobodyInTheShopCanSeeAndCarriesTheViewsAndTheNote() throws Exception {
        JsonNode product = twoOptionProduct();
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        List<String> before = shopPhotos(red);

        JsonNode batch = runOnOption(red, optionRunBody("[\"CLEAN\",\"TOP_DOWN\"]", "on pink marble, lid off", false));

        assertThat(batch.get("publishMode").asText()).isEqualTo("REVIEW");
        assertThat(batch.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(batch.get("views")).extracting(JsonNode::asText).containsExactly("CLEAN", "TOP_DOWN");
        assertThat(batch.get("note").asText()).isEqualTo("on pink marble, lid off");
        assertThat(batch.get("pendingDrafts").asInt()).isEqualTo(2);

        List<JsonNode> waiting = drafts(productId);
        assertThat(waiting).hasSize(2);
        assertThat(waiting).extracting(d -> d.get("viewKey").asText()).containsExactlyInAnyOrder("CLEAN", "TOP_DOWN");
        assertThat(waiting).allSatisfy(d -> {
            assertThat(d.get("variantId").asText()).isEqualTo(red);
            assertThat(d.get("optionName").asText()).isEqualTo("Red");
            assertThat(d.get("note").asText()).isEqualTo("on pink marble, lid off");
            assertThat(d.get("referenceUrl").asText()).contains("red-original");
        });
        assertThat(shopPhotos(red)).as("a draft is invisible to shoppers").isEqualTo(before);
        assertThat(staff(get("/api/v1/products/" + productId + "/images"), null)).as("and is not a photo yet").hasSize(2);

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(gemini, org.mockito.Mockito.times(2)).edit(any(), prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(p -> assertThat(p).contains("on pink marble, lid off"));
        assertThat(prompts.getAllValues()).anySatisfy(p -> assertThat(p).contains("looking straight down"));
    }

    @Test
    void approvingMakesAnOrdinaryPhotoOfThatOptionOnlyAndCanBeTheMainOne() throws Exception {
        JsonNode product = twoOptionProduct();
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        String blue = optionId(product, 1);
        List<String> redBefore = shopPhotos(red);
        List<String> blueBefore = shopPhotos(blue);
        runOnOption(red, optionRunBody("[\"CLEAN\",\"FRONT\"]", null, false));
        List<JsonNode> waiting = drafts(productId);
        JsonNode first = waiting.stream().filter(d -> d.get("viewKey").asText().equals("CLEAN")).findFirst().orElseThrow();
        JsonNode second = waiting.stream().filter(d -> d.get("viewKey").asText().equals("FRONT")).findFirst().orElseThrow();

        JsonNode approved = staff(post("/api/v1/admin/image-cleanup/drafts/" + first.get("id").asText() + "/approve"), "{\"makeMain\":false}");

        assertThat(approved.get("variantId").asText()).isEqualTo(red);
        assertThat(approved.get("source").asText()).isEqualTo("AI_CLEANED");
        assertThat(approved.get("isPrimary").asBoolean()).isFalse();
        assertThat(shopPhotos(red)).as("added after its own photo").containsExactly(redBefore.get(0), first.get("url").asText());
        assertThat(shopPhotos(blue)).as("another option never gets it").isEqualTo(blueBefore);

        JsonNode main = staff(post("/api/v1/admin/image-cleanup/drafts/" + second.get("id").asText() + "/approve"), "{\"makeMain\":true}");
        assertThat(main.get("isPrimary").asBoolean()).isTrue();
        assertThat(shopPhotos(red)).containsExactly(second.get("url").asText(), redBefore.get(0), first.get("url").asText());
        assertThat(drafts(productId)).as("nothing left waiting").isEmpty();
        assertThat(shopPhotos(blue)).isEqualTo(blueBefore);
    }

    @Test
    void rejectingADraftDeletesItsFileOnCloudinaryToo() throws Exception {
        JsonNode product = twoOptionProduct();
        String productId = product.get("id").asText();
        runOnOption(optionId(product, 1), optionRunBody("[\"WIDE\"]", null, false));
        JsonNode draft = drafts(productId).get(0);
        String file = draft.get("url").asText().substring(draft.get("url").asText().lastIndexOf('/') + 1).replace(".png", "");

        call(delete("/api/v1/admin/image-cleanup/drafts/" + draft.get("id").asText()), null);

        assertThat(drafts(productId)).isEmpty();
        verify(cloudinary).delete("products/" + file);
    }

    @Test
    void redoMakesAnotherDraftWithTheNewNoteAndLeavesTheFirstToCompareWith() throws Exception {
        JsonNode product = twoOptionProduct();
        String productId = product.get("id").asText();
        runOnOption(optionId(product, 0), optionRunBody("[\"SIDE\"]", "plain white", false));
        JsonNode original = drafts(productId).get(0);

        JsonNode redo = staff(post("/api/v1/admin/image-cleanup/drafts/" + original.get("id").asText() + "/redo"), "{\"note\":\"softer shadow\"}");
        UUID redoBatch = UUID.fromString(redo.get("id").asText());
        await().atMost(Duration.ofSeconds(30)).until(
                () -> !"IN_PROGRESS".equals(staff(get("/api/v1/admin/image-cleanup/batches/" + redoBatch), null).get("status").asText()));

        List<JsonNode> waiting = drafts(productId);
        assertThat(waiting).hasSize(2);
        assertThat(waiting).extracting(d -> d.get("note").asText()).containsExactlyInAnyOrder("plain white", "softer shadow");
        assertThat(waiting).extracting(d -> d.get("viewKey").asText()).containsOnly("SIDE");
        assertThat(waiting).extracting(d -> d.get("referenceImageId").asText()).containsOnly(original.get("referenceImageId").asText());
    }

    // ---- go live now ----

    @Test
    void goLiveNowPublishesAtOnceFirstAndMainAndUndoPutsTheOriginalBack() throws Exception {
        JsonNode product = twoOptionProduct();
        String red = optionId(product, 0);
        String blue = optionId(product, 1);
        List<String> redBefore = shopPhotos(red);
        List<String> blueBefore = shopPhotos(blue);

        JsonNode batch = runOnOption(red, optionRunBody("[\"CLEAN\",\"DETAIL\"]", null, true));

        assertThat(batch.get("publishMode").asText()).isEqualTo("IMMEDIATE");
        List<String> after = shopPhotos(red);
        assertThat(after).hasSize(3);
        assertThat(after.get(2)).as("the original is kept after the new ones").isEqualTo(redBefore.get(0));
        assertThat(after.subList(0, 2)).allSatisfy(url -> assertThat(url).contains("/products/"));
        assertThat(shopPhotos(blue)).isEqualTo(blueBefore);

        JsonNode undone = staff(delete("/api/v1/admin/image-cleanup/batches/" + batch.get("id").asText()), null);

        assertThat(undone.get("status").asText()).isEqualTo("DELETED");
        assertThat(shopPhotos(red)).isEqualTo(redBefore);
        verify(cloudinary, org.mockito.Mockito.times(2)).delete(org.mockito.ArgumentMatchers.startsWith("products/"));
    }

    @Test
    void undoingARunDiscardsItsWaitingDraftsAndTheirFiles() throws Exception {
        JsonNode product = twoOptionProduct();
        JsonNode batch = runOnOption(optionId(product, 0), optionRunBody("[\"CLEAN\",\"BACK\"]", null, false));
        assertThat(drafts(product.get("id").asText())).hasSize(2);

        staff(delete("/api/v1/admin/image-cleanup/batches/" + batch.get("id").asText()), null);

        assertThat(drafts(product.get("id").asText())).isEmpty();
        verify(cloudinary, org.mockito.Mockito.times(2)).delete(org.mockito.ArgumentMatchers.startsWith("products/"));
    }

    // ---- who may be worked on ----

    @Test
    void anOptionWithNoRealPhotoCannotBeWorkedOn() throws Exception {
        JsonNode product = staff(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Studio Bare")
                + "\",\"categoryIds\":[],\"published\":true,\"variants\":[{\"variantName\":\"Only\",\"sellingPrice\":\"100\",\"isActive\":true}]}");

        MvcResult result = call(post("/api/v1/admin/image-cleanup/options/" + optionId(product, 0) + "/run"),
                optionRunBody("[\"CLEAN\"]", null, false));

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("no real photo");
        verify(gemini, never()).edit(any(), anyString());
    }

    @Test
    void aStartingPhotoOfAnotherOptionIsRefusedAndSoIsAnUnknownView() throws Exception {
        JsonNode product = twoOptionProduct();
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        String blueOriginal = null;
        for (JsonNode image : staff(get("/api/v1/products/" + productId + "/images"), null)) {
            if (image.get("variantId").asText().equals(optionId(product, 1))) {
                blueOriginal = image.get("id").asText();
            }
        }
        assertThat(blueOriginal).as("blue's own photo").isNotNull();

        MvcResult foreign = call(post("/api/v1/admin/image-cleanup/options/" + red + "/run"),
                "{\"sourceImageId\":\"" + blueOriginal + "\",\"views\":[\"CLEAN\"],\"publishNow\":false}");
        assertThat(foreign.getResponse().getStatus()).isEqualTo(422);

        MvcResult unknown = call(post("/api/v1/admin/image-cleanup/options/" + red + "/run"),
                "{\"views\":[\"SIDEWAYS\"],\"publishNow\":false}");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(unknown.getResponse().getContentAsString()).contains("Unknown photo view");
        verify(gemini, never()).edit(any(), anyString());
    }

    @Test
    void bulkCandidatesAreOptionsWhoseMainPhotoIsRealAndThatHaveNoAiPhotoYet() throws Exception {
        JsonNode product = twoOptionProduct();
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        String blue = optionId(product, 1);
        runOnOption(red, optionRunBody("[\"CLEAN\"]", null, true));

        List<UUID> candidateOptions = images.cleanupCandidates(ProductImage.ImageSource.AI_CLEANED,
                        org.springframework.data.domain.PageRequest.of(0, 5000)).stream()
                .map(i -> i.getVariant().getId()).toList();

        assertThat(candidateOptions).contains(UUID.fromString(blue)).doesNotContain(UUID.fromString(red));
        assertThat(productId).isNotBlank();
        assertThat(staff(get("/api/v1/admin/image-cleanup/status"), null).get("views")).hasSize(8);
    }

    // ---- housekeeping ----

    @Test
    void draftsNobodyDecidedOnLapseAndTakeTheirFilesWithThem() throws Exception {
        JsonNode product = twoOptionProduct();
        String productId = product.get("id").asText();
        runOnOption(optionId(product, 0), optionRunBody("[\"ELEVATED\"]", null, false));
        JsonNode draft = drafts(productId).get(0);
        jdbc.update("UPDATE image_draft SET created_at = now() - interval '20 days' WHERE id = ?", UUID.fromString(draft.get("id").asText()));

        draftService.expireOld();

        assertThat(drafts(productId)).isEmpty();
        verify(cloudinary).delete(org.mockito.ArgumentMatchers.startsWith("products/"));
    }

    @Test
    void removingAnOptionDiscardsTheDraftsWaitingForIt() throws Exception {
        JsonNode product = twoOptionProduct();
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        String blue = optionId(product, 1);
        runOnOption(blue, optionRunBody("[\"CLEAN\"]", null, false));
        assertThat(drafts(productId)).hasSize(1);

        staff(put("/api/v1/products/" + productId + "/full"), "{\"expectedUpdatedAt\":\"" + product.get("updatedAt").asText()
                + "\",\"name\":\"" + product.get("name").asText() + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[{\"id\":\"" + red + "\",\"variantName\":\"Red\",\"sellingPrice\":\"400\",\"isActive\":true}]}");

        assertThat(drafts(productId)).isEmpty();
        verify(cloudinary).delete(org.mockito.ArgumentMatchers.startsWith("products/"));
    }
}
