package com.nbh.backend.banners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.PhotoStudioTestContext;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The banner builder (owner, 2026-10-31): a banner is made on purpose, gets its picture only when staff ask, cannot be
 * approved until a customer would see something whole, shows only between its dates, goes back to a draft when changed,
 * and the old slots' banners reach the shop through the occasion view. Gemini and Cloudinary are stand-ins.
 */
class PromoBannerIntegrationTest extends PhotoStudioTestContext {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;

    private String token;
    private final List<String> made = new ArrayList<>();

    @BeforeEach
    void signInAndStandIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
        reset(gemini, cloudinary);
        when(cloudinary.configured()).thenReturn(true);
        when(gemini.download(anyString())).thenReturn(new GeminiImageClient.Image(new byte[]{1}, "image/png"));
        when(gemini.edit(any(), anyList(), anyString(), anyString()))
                .thenReturn(new GeminiImageClient.Image(new byte[]{2}, "image/png"));
        when(cloudinary.upload(any(), anyString(), anyString())).thenAnswer(inv -> new CloudinaryUploader.Uploaded(
                "https://res.cloudinary.com/demo/" + inv.getArgument(2) + ".png", "promo/" + inv.getArgument(2)));
    }

    /** No banner of these tests stays on the shop for the next test class. */
    @AfterEach
    void takeThemDown() {
        made.forEach(id -> jdbc.update("UPDATE promo_banner SET status = 'PAUSED' WHERE id = ?::uuid", id));
    }

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

    private String refused(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult r = call(request, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(422);
        return r.getResponse().getContentAsString();
    }

    /** A published product with a photo and a live deal of 450 → 360 (20% off). Returns its variant id. */
    private String productOnDeal() throws Exception {
        String name = "Banner Shea " + UUID.randomUUID().toString().substring(0, 8);
        JsonNode product = ok(post("/api/v1/products/draft"),
                "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,\"variants\":["
                        + "{\"variantName\":\"Default\",\"sellingPrice\":\"450\",\"isActive\":true}]}");
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(post("/api/v1/products/" + product.get("id").asText() + "/images"),
                "{\"url\":\"https://photos.test/shea-" + UUID.randomUUID().toString().substring(0, 6)
                        + ".jpg\",\"isPrimary\":true,\"variantId\":\"" + variantId + "\"}");
        jdbc.update("UPDATE product_variant SET deal_price = 360, deal_ends_at = now() + interval '1 day' WHERE id = ?::uuid",
                variantId);
        return variantId;
    }

    private JsonNode create(String placement) throws Exception {
        JsonNode b = ok(post("/api/v1/admin/promo-banners"), "{\"placement\":\"" + placement + "\"}");
        made.add(b.get("id").asText());
        return b;
    }

    private String edit(String headline, String productId, Instant starts, Instant ends) {
        return "{\"headline\":\"" + headline + "\",\"subline\":\"Shea butter for every skin\",\"ctaLabel\":\"Shop now\","
                + "\"linkType\":\"DEALS\",\"offerMode\":\"AUTO\",\"showCountdown\":true,\"textTone\":\"light\","
                + "\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"productIds\":["
                + (productId == null ? "" : "\"" + productId + "\"") + "]}";
    }

    private JsonNode waitForPictures(String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode b = ok(get("/api/v1/admin/promo-banners/" + id), null);
            boolean making = false;
            for (JsonNode p : b.get("pictures")) making |= "MAKING".equals(p.get("status").asText());
            if (!making) return b;
            Thread.sleep(100);
        }
        throw new AssertionError("pictures were still being made");
    }

    private static boolean listed(JsonNode list, String id) {
        for (JsonNode b : list) if (id.equals(b.get("id").asText())) return true;
        return false;
    }

    @Test
    void aHeroIsBuiltPicturedApprovedAndShownOnlyBetweenItsDates() throws Exception {
        String product = productOnDeal();
        JsonNode b = create("HERO");
        String id = b.get("id").asText();
        assertThat(b.get("status").asText()).isEqualTo("DRAFT");
        assertThat(b.get("missing").toString()).contains("choose a picture", "write a headline");

        Instant now = Instant.now();
        b = ok(put("/api/v1/admin/promo-banners/" + id), edit("Glow for less", product, now.minus(1, ChronoUnit.HOURS),
                now.plus(2, ChronoUnit.DAYS)));
        assertThat(b.get("offer").asText()).as("worked out from the featured deal").isEqualTo("Up to 20% off");
        assertThat(b.get("products").get(0).get("dealPrice").decimalValue()).isEqualByComparingTo("360");
        assertThat(refused(post("/api/v1/admin/promo-banners/" + id + "/approve"), null)).contains("choose a picture");

        ok(post("/api/v1/admin/promo-banners/" + id + "/pictures/generate"), "{\"brief\":\"soft pink glam\",\"count\":2}");
        b = waitForPictures(id);
        assertThat(b.get("pictures")).hasSize(2);
        assertThat(b.get("pictures").get(0).get("status").asText()).isEqualTo("READY");
        verify(gemini, times(2)).edit(any(), anyList(), contains("soft pink glam"), eq("16:9"));
        String pictureId = b.get("pictures").get(0).get("id").asText();
        b = ok(post("/api/v1/admin/promo-banners/" + id + "/pictures/" + pictureId + "/choose"), null);
        assertThat(b.get("imageUrl").asText()).startsWith("https://res.cloudinary.com/demo/promo-hero-");
        assertThat(b.get("missing")).isEmpty();
        assertThat(listed(ok(get("/api/v1/catalog/promo-banners"), null), id)).as("not before approval").isFalse();

        b = ok(post("/api/v1/admin/promo-banners/" + id + "/approve"), null);
        assertThat(b.get("state").asText()).isEqualTo("LIVE");
        JsonNode live = ok(get("/api/v1/catalog/promo-banners"), null);
        assertThat(listed(live, id)).isTrue();

        // changing what customers see takes it back to a draft, off the shop until approved again
        b = ok(put("/api/v1/admin/promo-banners/" + id), edit("Glow for even less", product,
                now.minus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.DAYS)));
        assertThat(b.get("state").asText()).isEqualTo("DRAFT");
        assertThat(listed(ok(get("/api/v1/catalog/promo-banners"), null), id)).isFalse();

        ok(post("/api/v1/admin/promo-banners/" + id + "/approve"), null);
        b = ok(post("/api/v1/admin/promo-banners/" + id + "/pause"), null);
        assertThat(b.get("state").asText()).isEqualTo("PAUSED");
        assertThat(listed(ok(get("/api/v1/catalog/promo-banners"), null), id)).isFalse();
        ok(post("/api/v1/admin/promo-banners/" + id + "/resume"), null);
        b = ok(post("/api/v1/admin/promo-banners/" + id + "/end"), null);
        assertThat(b.get("state").asText()).isEqualTo("ENDED");
        assertThat(refused(delete("/api/v1/admin/promo-banners/" + id), null)).contains("end it instead");
    }

    @Test
    void aScheduledBannerWaitsAndAFailedPictureSaysWhy() throws Exception {
        String product = productOnDeal();
        String id = create("STRIP").get("id").asText();
        Instant now = Instant.now();
        ok(put("/api/v1/admin/promo-banners/" + id), edit("Payday picks", product, now.plus(1, ChronoUnit.DAYS),
                now.plus(3, ChronoUnit.DAYS)));

        when(gemini.edit(any(), anyList(), anyString(), anyString())).thenThrow(new GeminiException("Gemini timed out.", false));
        ok(post("/api/v1/admin/promo-banners/" + id + "/pictures/generate"), "{\"count\":1}");
        JsonNode b = waitForPictures(id);
        assertThat(b.get("pictures").get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(b.get("pictures").get(0).get("note").asText()).contains("Gemini timed out.");

        b = ok(post("/api/v1/admin/promo-banners/" + id + "/pictures/from-products"), null);
        String photo = b.get("pictures").get(0).get("id").asText();
        assertThat(b.get("pictures").get(0).get("source").asText()).isEqualTo("PRODUCT");
        ok(post("/api/v1/admin/promo-banners/" + id + "/pictures/" + photo + "/choose"), null);
        b = ok(post("/api/v1/admin/promo-banners/" + id + "/approve"), null);
        assertThat(b.get("state").asText()).isEqualTo("SCHEDULED");
        assertThat(listed(ok(get("/api/v1/catalog/promo-banners"), null), id)).isFalse();
    }

    @Test
    void anOffersRowCardReachesTheShopThroughTheOccasionView() throws Exception {
        String product = productOnDeal();
        String id = create("OFFER_CARD").get("id").asText();
        Instant now = Instant.now();
        ok(put("/api/v1/admin/promo-banners/" + id), edit("Restock for payday", product, now.minus(1, ChronoUnit.HOURS),
                now.plus(1, ChronoUnit.DAYS)));
        ok(post("/api/v1/admin/promo-banners/" + id + "/pictures/upload"),
                "{\"imageUrl\":\"https://res.cloudinary.com/demo/own-card.png\"}");
        ok(post("/api/v1/admin/promo-banners/" + id + "/approve"), null);

        JsonNode shop = ok(get("/api/v1/catalog/occasion"), null);
        assertThat(shop.get("occasion").isNull()).as("the shop needs a frame to draw the slot in").isFalse();
        JsonNode card = null;
        for (JsonNode b : shop.get("banners")) if (id.equals(b.get("id").asText())) card = b;
        assertThat(card).isNotNull();
        assertThat(card.get("slot").asText()).isEqualTo("OFFER_CARD");
        assertThat(card.get("line").asText()).isEqualTo("Restock for payday");
        assertThat(card.get("imageUrl").asText()).isEqualTo("https://res.cloudinary.com/demo/own-card.png");
        assertThat(listed(ok(get("/api/v1/catalog/promo-banners"), null), id)).as("small slots aren't heroes").isFalse();
    }

    @Test
    void aDraftCanBeDuplicatedAndDeleted() throws Exception {
        String id = create("TILE").get("id").asText();
        ok(post("/api/v1/admin/promo-banners/" + id + "/pictures/upload"),
                "{\"imageUrl\":\"https://res.cloudinary.com/demo/tile.png\"}");
        JsonNode copy = ok(post("/api/v1/admin/promo-banners/" + id + "/duplicate"), null);
        made.add(copy.get("id").asText());
        assertThat(copy.get("name").asText()).endsWith("(copy)");
        assertThat(copy.get("imageUrl").asText()).isEqualTo("https://res.cloudinary.com/demo/tile.png");
        ok(delete("/api/v1/admin/promo-banners/" + copy.get("id").asText()), null);
        assertThat(call(get("/api/v1/admin/promo-banners/" + copy.get("id").asText()), null).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(refused(post("/api/v1/admin/promo-banners"), "{\"placement\":\"BILLBOARD\"}"))
                .contains("Choose where the banner shows");
    }
}
