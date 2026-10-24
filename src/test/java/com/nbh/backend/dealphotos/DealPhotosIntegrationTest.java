package com.nbh.backend.dealphotos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.PhotoStudioTestContext;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Deal pictures (owner, 2026-10-24): a product on a flash deal gets a promotional picture made from its real photo,
 * staff use it or not, and the shop shows the used one on its card only while the product's own deal is live. Gemini and
 * Cloudinary are stand-ins (the photo tests' shared context); everything else is the real application and database.
 */
class DealPhotosIntegrationTest extends PhotoStudioTestContext {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DealPhotoService service;
    @Autowired private DealPhotoRepository photos;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void signInAndStandIn() throws Exception {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
        reset(gemini, cloudinary);
        when(cloudinary.configured()).thenReturn(true);
        when(gemini.download(anyString())).thenReturn(new GeminiImageClient.Image(new byte[]{1}, "image/png"));
        when(gemini.edit(any(), anyList(), anyString())).thenReturn(new GeminiImageClient.Image(new byte[]{2}, "image/png"));
        when(cloudinary.upload(any(), anyString(), anyString())).thenAnswer(inv -> new CloudinaryUploader.Uploaded(
                "https://res.cloudinary.com/demo/deals/" + inv.getArgument(2) + ".png", "deals/" + inv.getArgument(2)));
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + bearer)).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = call(request, token);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    /** A published one-option product on a live flash deal; with a photo unless told otherwise. Returns its variant id. */
    private UUID productOnDeal(boolean withPhoto) throws Exception {
        String name = "Deal Shea " + UUID.randomUUID().toString().substring(0, 8);
        JsonNode product = ok(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,\"variants\":["
                        + "{\"variantName\":\"Default\",\"sellingPrice\":\"450\",\"isActive\":true}]}"));
        String variantId = product.get("variants").get(0).get("id").asText();
        if (withPhoto) {
            ok(post("/api/v1/products/" + product.get("id").asText() + "/images").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://photos.test/shea-" + UUID.randomUUID().toString().substring(0, 6)
                            + ".jpg\",\"isPrimary\":true,\"variantId\":\"" + variantId + "\"}"));
        }
        jdbc.update("UPDATE product_variant SET deal_price = 360, deal_ends_at = now() + interval '1 day' WHERE id = ?::uuid",
                variantId);
        return UUID.fromString(variantId);
    }

    private JsonNode card(UUID variantId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/catalog/variants/" + variantId)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("card");
    }

    @Test
    void aPictureIsMadeFromTheRealPhotoWaitsForStaffAndShowsOnTheCardOnlyOnceUsed() throws Exception {
        UUID variantId = productOnDeal(true);

        var made = service.make(variantId);
        assertThat(made.status()).isEqualTo("PENDING");
        assertThat(made.url()).startsWith("https://res.cloudinary.com/demo/deals/");
        assertThat(made.originalUrl()).startsWith("https://photos.test/shea-");
        assertThat(card(variantId).get("dealImageUrl").isNull()).as("not shown before staff use it").isTrue();

        JsonNode waiting = ok(get("/api/v1/admin/deal-photos"));
        JsonNode mine = null;
        for (JsonNode w : waiting) if (w.get("id").asText().equals(made.id().toString())) mine = w;
        assertThat(mine).isNotNull();
        assertThat(mine.get("dealPrice").decimalValue()).isEqualByComparingTo("360");
        assertThat(ok(get("/api/v1/admin/deal-photos/count")).get("count").asLong()).isGreaterThanOrEqualTo(1);

        ok(post("/api/v1/admin/deal-photos/" + made.id() + "/approve"));
        assertThat(card(variantId).get("dealImageUrl").asText()).isEqualTo(made.url());
        // the product's own photos are untouched: the deal picture is not in its gallery
        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/catalog/variants/" + variantId)).andReturn()
                .getResponse().getContentAsString());
        detail.get("images").forEach(i -> assertThat(i.get("url").asText()).doesNotContain("/deals/"));

        // when the deal ends the card goes back to the product's own photo
        jdbc.update("UPDATE product_variant SET deal_ends_at = now() - interval '1 minute' WHERE id = ?::uuid", variantId);
        assertThat(card(variantId).get("dealImageUrl").isNull()).isTrue();
    }

    @Test
    void usingAnotherPictureSetsTheEarlierOneAsideAndADecidedOneCannotBeDecidedAgain() throws Exception {
        UUID variantId = productOnDeal(true);
        var first = service.make(variantId);
        ok(post("/api/v1/admin/deal-photos/" + first.id() + "/approve"));

        JsonNode second = ok(post("/api/v1/admin/deal-photos/variant/" + variantId + "/make"));
        assertThat(second.get("status").asText()).isEqualTo("PENDING");
        ok(post("/api/v1/admin/deal-photos/" + second.get("id").asText() + "/approve"));

        assertThat(photos.findByVariantIdAndStatus(variantId, DealPhoto.Status.APPROVED)).hasSize(1);
        assertThat(card(variantId).get("dealImageUrl").asText()).isEqualTo(second.get("url").asText());
        assertThat(call(post("/api/v1/admin/deal-photos/" + first.id() + "/approve"), token).getResponse().getStatus())
                .isEqualTo(422);

        var third = service.make(variantId);
        ok(post("/api/v1/admin/deal-photos/" + third.id() + "/reject"));
        assertThat(card(variantId).get("dealImageUrl").asText()).as("a rejected one changes nothing")
                .isEqualTo(second.get("url").asText());
    }

    @Test
    void theJobPicksUpADealWithoutAPictureButNotOneWithoutAPhotoOrOneAlreadyTried() throws Exception {
        UUID withPhoto = productOnDeal(true);
        UUID withoutPhoto = productOnDeal(false);

        // other tests' deals may be waiting too: run until ours has been tried (a few at a time, ending soonest first)
        for (int i = 0; i < 40 && photos.findByVariantIdAndStatus(withPhoto, DealPhoto.Status.PENDING).isEmpty(); i++) {
            service.runOnce();
        }
        assertThat(photos.findByVariantIdAndStatus(withPhoto, DealPhoto.Status.PENDING)).hasSize(1);

        var failed = service.make(withoutPhoto);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.note()).contains("no photo");
        for (int i = 0; i < 3; i++) service.runOnce();
        assertThat(photos.findByVariantIdAndStatus(withPhoto, DealPhoto.Status.PENDING)).as("tried once, not again").hasSize(1);
        assertThat(photos.findByVariantIdAndStatus(withoutPhoto, DealPhoto.Status.FAILED)).hasSize(1);
    }

    @Test
    void aGeminiLimitLeavesNoRecordSoTheJobTriesAgainLater() throws Exception {
        UUID variantId = productOnDeal(true);
        when(gemini.edit(any(), anyList(), anyString())).thenThrow(new GeminiException("RESOURCE_EXHAUSTED", true));
        MvcResult refused = call(post("/api/v1/admin/deal-photos/variant/" + variantId + "/make"), token);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("limit");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deal_photo WHERE variant_id = ?::uuid", Long.class,
                variantId.toString())).isZero();
    }

    @Test
    void onlyStaffWhoManageProductsMayDecide() throws Exception {
        StaffUser cashier = new StaffUser();
        cashier.setEmail("deal-cashier-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        cashier.setPassword(passwordEncoder.encode("irrelevant-password"));
        cashier.setFirstName("Cashier");
        cashier.setLastName("Tester");
        cashier.setStaffRole(staffRoles.findByName("CASHIER").orElseThrow());
        String cashierToken = jwtService.generateAccessToken(staffUsers.save(cashier));
        assertThat(call(get("/api/v1/admin/deal-photos"), cashierToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/admin/deal-photos")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }
}
