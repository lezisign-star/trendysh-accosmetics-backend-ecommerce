package com.nbh.backend.banners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Occasion banners (owner, 2026-10-29): only picked banners of the occasion on now reach the shop; picking one retires
 * the one picked before for the same slot; staff can change the line, add an occasion of their own, and switch one off.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BannerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private OccasionRepository occasions;
    @Autowired private BannerRepository banners;
    @Autowired private BannerService service;

    private String token;

    @BeforeEach
    void signInAsSuperAdmin() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body, boolean staff) throws Exception {
        if (staff) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body, boolean staff) throws Exception {
        MvcResult r = call(request, body, staff);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String content = r.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private Banner waiting(UUID occasionId, String line) {
        Banner b = new Banner();
        b.setOccasionId(occasionId);
        b.setSlot(Banner.Slot.OFFER_CARD);
        b.setStyle(Banner.Style.CARTOON_JOKE);
        b.setContainer("card-words-bottom");
        b.setImageUrl("https://res.cloudinary.com/demo/banner-" + UUID.randomUUID() + ".png");
        b.setLine(line);
        b.setTextTone("light");
        b.setStatus(Banner.Status.WAITING);
        b.setOfferSignature("test-" + UUID.randomUUID());
        return banners.save(b);
    }

    @Test
    void theFourOccasionsAreThereAndTheSlotsCanBeWorkedOut() throws Exception {
        JsonNode list = ok(get("/api/v1/admin/occasions"), null, true);
        assertThat(list.findValuesAsText("code")).contains("weekend", "payday", "black-november", "december-festive");
        // The targets' queries run against the real tables (the offers card and grid tile always, circles per category).
        assertThat(service.targets()).isNotNull();
        assertThat(call(get("/api/v1/admin/banners"), null, false).getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    void onlyPickedBannersOfTheOccasionOnNowReachTheShop() throws Exception {
        String name = "QA Day " + UUID.randomUUID().toString().substring(0, 6);
        ok(post("/api/v1/admin/occasions"), "{\"name\":\"" + name + "\",\"startsOn\":\"" + LocalDate.now().minusDays(1)
                + "\",\"endsOn\":\"" + LocalDate.now().plusDays(1) + "\",\"moodLine\":\"Karibu for the day\"}", true);
        Occasion mine = occasions.findAll().stream().filter(o -> o.getName().equals(name)).findFirst().orElseThrow();

        JsonNode shop = ok(get("/api/v1/catalog/occasion"), null, false);
        assertThat(shop.path("occasion").path("name").asText()).isEqualTo(name);
        assertThat(shop.path("occasion").path("moodLine").asText()).isEqualTo("Karibu for the day");

        Banner first = waiting(mine.getId(), "Weekend iko sawa");
        Banner second = waiting(mine.getId(), "Sasa glow");
        assertThat(ok(get("/api/v1/catalog/occasion"), null, false).path("banners")).isEmpty();

        ok(post("/api/v1/admin/banners/" + first.getId() + "/pick"), null, true);
        JsonNode after = ok(get("/api/v1/catalog/occasion"), null, false).path("banners");
        assertThat(after).hasSize(1);
        assertThat(after.get(0).path("line").asText()).isEqualTo("Weekend iko sawa");
        assertThat(after.get(0).path("container").asText()).isEqualTo("card-words-bottom");

        // picking the other version retires the first; staff's own line shows
        ok(patch("/api/v1/admin/banners/" + second.getId()), "{\"line\":\"Glow for the day\"}", true);
        ok(post("/api/v1/admin/banners/" + second.getId() + "/pick"), null, true);
        after = ok(get("/api/v1/catalog/occasion"), null, false).path("banners");
        assertThat(after).hasSize(1);
        assertThat(after.get(0).path("line").asText()).isEqualTo("Glow for the day");
        assertThat(banners.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(Banner.Status.RETIRED);
        assertThat(call(patch("/api/v1/admin/banners/" + second.getId()), "{\"line\":\"" + "x".repeat(61) + "\"}", true)
                .getResponse().getStatus()).isEqualTo(422);

        // switched off: the shop no longer shows it or its banners
        ok(put("/api/v1/admin/occasions/" + mine.getId()), "{\"enabled\":false}", true);
        JsonNode off = ok(get("/api/v1/catalog/occasion"), null, false);
        assertThat(off.path("occasion").path("name").asText("")).isNotEqualTo(name);
        assertThat(off.path("banners").findValuesAsText("id")).doesNotContain(second.getId().toString());
    }
}
