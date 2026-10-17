package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * AI-written variant descriptions (owner, 2026-10-17): drafted from the range's shared description for staff to read,
 * only for variants without their own unless others are ticked, and stored only when staff save them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RangeDescriptionsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ImageCleanupConfig config;
    @Autowired private IntegrationSettingsService integrations;

    private HttpServer stub;
    private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
    private String originalGeminiUrl;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            prompts.add(request);
            // One description per numbered variant in the prompt, naming the variant it was written for.
            StringBuilder items = new StringBuilder();
            Matcher m = Pattern.compile("\\\\n(\\d+): \\\\\"([^\\\\]+)\\\\\"").matcher(request);
            while (m.find()) {
                if (items.length() > 0) items.append(',');
                items.append("{\"index\":").append(m.group(1)).append(",\"description\":\"Written for ")
                        .append(m.group(2)).append(".\"}");
            }
            String answer = "{\"variants\":[" + items + "]}";
            byte[] reply = json.writeValueAsBytes(Map.of("candidates", List.of(Map.of(
                    "content", Map.of("parts", List.of(Map.of("text", answer)))))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        stub.start();
        originalGeminiUrl = config.getGeminiBaseUrl();
        config.setGeminiBaseUrl("http://127.0.0.1:" + stub.getAddress().getPort());
        integrations.set(IntegrationKey.GEMINI_API_KEY, "test-key", null);
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
        config.setGeminiBaseUrl(originalGeminiUrl);
        integrations.clear(IntegrationKey.GEMINI_API_KEY);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult r = call(request, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    @Test
    void draftsComeFromTheSharedTextForVariantsWithoutTheirOwnAndAreStoredOnlyWhenSaved() throws Exception {
        String w = "zd" + UUID.randomUUID().toString().replaceAll("[^a-f]", "").substring(0, 6);
        String id = ok(post("/api/v1/products/draft"), "{\"name\":\"Roniki Gel " + w + "\",\"categoryIds\":[],"
                + "\"published\":false,\"description\":\"A soak-off gel polish that lasts three weeks.\",\"variants\":["
                + "{\"variantName\":\"Roniki Gel " + w + " – Bubblegum Pink RE106\",\"sellingPrice\":\"350\",\"isActive\":true},"
                + "{\"variantName\":\"Roniki Gel " + w + " – Lilac Purple PU19\",\"sellingPrice\":\"350\",\"isActive\":true,"
                + "\"description\":\"Already its own.\"}]}").get("id").asText();
        MvcResult early = call(put("/api/v1/products/" + id + "/range/descriptions"), "{\"items\":[]}");
        assertThat(early.getResponse().getStatus()).as("a range not confirmed yet").isEqualTo(422);
        ok(put("/api/v1/products/" + id + "/range"), "{\"name\":\"Roniki Gel " + w + "\","
                + "\"description\":\"A soak-off gel polish that lasts three weeks.\",\"sharedPrice\":350,"
                + "\"categoryIds\":[],\"priceVaries\":false,\"sizeVaries\":false,\"descriptionVaries\":true,"
                + "\"categoryVaries\":false}");

        JsonNode drafts = ok(post("/api/v1/products/" + id + "/range/descriptions/write"), "{}");
        assertThat(drafts).as("only the variant without its own description").singleElement().satisfies(d ->
                assertThat(d.get("description").asText()).isEqualTo("Written for Roniki Gel " + w + " – Bubblegum Pink RE106."));
        assertThat(prompts.get(0)).contains("lasts three weeks").contains("small touch of its own");
        JsonNode range = ok(get("/api/v1/products/" + id + "/range"), null);
        assertThat(range.get("variants").get(0).get("ownDescription").asBoolean()).as("nothing stored yet").isFalse();

        String pink = drafts.get(0).get("variantId").asText();
        ok(put("/api/v1/products/" + id + "/range/descriptions"),
                "{\"items\":[{\"variantId\":\"" + pink + "\",\"description\":\"Edited by staff.\"}]}");
        JsonNode product = ok(get("/api/v1/products/" + id), null);
        assertThat(product.get("variants").get(0).get("description").asText()).isEqualTo("Edited by staff.");
        assertThat(product.get("variants").get(1).get("description").asText()).isEqualTo("Already its own.");
    }
}
