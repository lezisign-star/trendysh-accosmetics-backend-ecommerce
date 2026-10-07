package com.nbh.backend.catalog;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Found in the 2026-10-06 live QA: "QA Test Category" and "qa test category" could both be created, side by side, and
 * then look identical in the shop's menu and in the product form. Two categories of the same name next to each other
 * are refused (whatever the capitals); the same name under a different parent is fine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryNamesIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;

    private String token;

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode create(String name, String parentId) throws Exception {
        MvcResult r = send(post("/api/v1/categories"),
                "{\"name\":\"" + name + "\"" + (parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"") + "}");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aCategoryWithTheSameNameBesideAnotherIsRefusedWhateverTheCapitals() throws Exception {
        String name = unique("Hair Tools");
        create(name, null);

        MvcResult refused = send(post("/api/v1/categories"), "{\"name\":\"  " + name.toUpperCase() + " \"}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("There is already a category called");
    }

    @Test
    void theSameNameUnderADifferentParentIsFineButNotTwiceUnderTheSameOne() throws Exception {
        String accessories = unique("Accessories");
        String nails = create(unique("Nails Parent"), null).get("id").asText();
        String lashes = create(unique("Lashes Parent"), null).get("id").asText();

        create(accessories, nails);
        create(accessories, lashes);

        MvcResult again = send(post("/api/v1/categories"),
                "{\"name\":\"" + accessories.toLowerCase() + "\",\"parentId\":\"" + nails + "\"}");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentAsString()).contains("under");
    }

    @Test
    void renamingToANameAlreadyBesideItIsRefusedButChangingOnlyTheCapitalsOfItsOwnNameIsNot() throws Exception {
        String first = unique("Brush Sets");
        String second = unique("Sponge Sets");
        create(first, null);
        String secondId = create(second, null).get("id").asText();

        MvcResult clash = send(patch("/api/v1/categories/" + secondId), "{\"name\":\"" + first.toLowerCase() + "\"}");
        assertThat(clash.getResponse().getStatus()).isEqualTo(409);

        MvcResult recased = send(patch("/api/v1/categories/" + secondId), "{\"name\":\"" + second.toUpperCase() + "\"}");
        assertThat(recased.getResponse().getStatus()).as(recased.getResponse().getContentAsString()).isEqualTo(200);
    }
}
