package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Brand;
import com.nbh.backend.catalog.entity.ProductOptionValue;
import com.nbh.backend.catalog.repository.BrandRepository;
import com.nbh.backend.catalog.repository.ProductOptionValueRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
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

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Variants v2, phase 2 (V63): a product's own colour and size list, kept in step with its variants by the product
 * editor's save, and what the shop gets from it (each card's colour, size, family, size position and brand; the
 * product page's colours and sizes in order). Unique names per run so it can share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductOptionsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductOptionValueRepository optionRows;
    @Autowired private ProductRepository products;
    @Autowired private BrandRepository brands;

    private String token;

    @BeforeEach
    void signInAsSuperAdmin() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body, boolean asStaff) throws Exception {
        if (asStaff) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body, true);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode shop(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = call(request, null, false);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** One variant for the editor's save; {@code options} null = the editor from before options existed. */
    private static String variant(String id, String name, String price, boolean active, String color, String size,
                                  String hex, String family, boolean withOptions) {
        return "{" + (id != null ? "\"id\":\"" + id + "\"," : "")
                + "\"variantName\":\"" + name + "\",\"sellingPrice\":\"" + price + "\",\"isActive\":" + active
                + (withOptions ? ",\"options\":{\"color\":" + q(color) + ",\"size\":" + q(size) + ",\"swatchHex\":"
                + q(hex) + ",\"colorFamily\":" + q(family) + "}" : "") + "}";
    }

    private static String opt(String name, String color, String size, String hex) {
        return variant(null, name, "650", true, color, size, hex, null, true);
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private JsonNode draft(String name, boolean published, String... variants) throws Exception {
        return ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":"
                + published + ",\"variants\":[" + String.join(",", variants) + "]}");
    }

    private JsonNode save(JsonNode product, String... variants) throws Exception {
        return ok(put("/api/v1/products/" + product.get("id").asText() + "/full"), "{\"name\":\""
                + product.get("name").asText() + "\",\"categoryIds\":[],\"published\":"
                + product.get("isPublished").asBoolean() + ",\"variants\":[" + String.join(",", variants) + "]}");
    }

    /** "value|position|swatch|family" for one kind of the product's stored list, in order. */
    private List<String> list(JsonNode product, ProductOptionValue.Kind kind) {
        return optionRows.findByProductIdOrderByKindAscPositionAsc(UUID.fromString(product.get("id").asText())).stream()
                .filter(o -> o.getKind() == kind)
                .map(o -> o.getValue() + "|" + o.getPosition() + "|" + o.getSwatchHex() + "|" + o.getColorFamily())
                .toList();
    }

    private static List<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get("value").asText()).toList();
    }

    private JsonNode card(String productName, String variantId) throws Exception {
        JsonNode content = shop(get("/api/v1/catalog/variants").param("q", productName).param("size", "50")).get("content");
        return StreamSupport.stream(content.spliterator(), false)
                .filter(c -> c.get("variantId").asText().equals(variantId)).findFirst().orElseThrow();
    }

    @Test
    void theEditorsColoursAndSizesBecomeTheProductsListAndEveryVariantFollowsIt() throws Exception {
        String name = unique("Velvet Matte");
        JsonNode p = draft(name, true,
                opt("Ruby Mini", "Ruby Red", "Mini 2g", "#9B111E"),
                // Second spelling, and no dot of its own: it takes the list's.
                opt("Ruby Full", " ruby  red ", "Full 3.5g", null),
                variant(null, "Nude Mini", "650", true, "Nude Beige", "Mini 2g", "c8a28a", "gold", true),
                opt("Nude Full", "Nude Beige", "Full 3.5g", null));
        JsonNode v = p.get("variants");

        assertThat(list(p, ProductOptionValue.Kind.COLOR))
                .containsExactly("Ruby Red|0|#9B111E|null", "Nude Beige|1|#C8A28A|GOLD");
        assertThat(list(p, ProductOptionValue.Kind.SIZE)).containsExactly("Mini 2g|0|null|null", "Full 3.5g|1|null|null");

        assertThat(v.get(1).get("optionColor").asText()).isEqualTo("Ruby Red");
        assertThat(v.get(1).get("swatchHex").asText()).isEqualTo("#9B111E");
        assertThat(v.get(1).get("optionLabel").asText()).isEqualTo("Ruby Red / Full 3.5g");
        assertThat(v.get(1).get("sizePosition").asInt()).isEqualTo(1);
        assertThat(v.get(2).get("colorFamily").asText()).as("staff's choice").isEqualTo("GOLD");
        assertThat(v.get(2).get("suggestedColorFamily").asText()).as("what the dot looks like").isEqualTo("NUDE");
        assertThat(v.get(3).get("colorFamily").asText()).as("the colour's family reaches its other sizes").isEqualTo("GOLD");
        assertThat(v.get(3).get("swatchHex").asText()).isEqualTo("#C8A28A");

        String rubyMini = v.get(0).get("id").asText();
        JsonNode card = card(name, rubyMini);
        assertThat(card.get("optionColor").asText()).isEqualTo("Ruby Red");
        assertThat(card.get("optionSize").asText()).isEqualTo("Mini 2g");
        assertThat(card.get("colorFamily").asText()).isEqualTo("RED");
        assertThat(card.get("sizePosition").asInt()).isEqualTo(0);
        assertThat(card.get("brandName").isNull()).isTrue();
        assertThat(card(name, v.get(3).get("id").asText()).get("colorFamily").asText()).isEqualTo("GOLD");

        JsonNode detail = shop(get("/api/v1/catalog/variants/" + rubyMini));
        assertThat(values(detail.get("colors"))).containsExactly("Ruby Red", "Nude Beige");
        assertThat(detail.get("colors").get(0).get("swatchHex").asText()).isEqualTo("#9B111E");
        assertThat(detail.get("colors").get(0).get("colorFamily").asText()).isEqualTo("RED");
        assertThat(detail.get("colors").get(1).get("colorFamily").asText()).isEqualTo("GOLD");
        assertThat(values(detail.get("sizes"))).containsExactly("Mini 2g", "Full 3.5g");
        assertThat(detail.get("siblings").get(0).get("optionSize").asText()).isEqualTo("Mini 2g");
    }

    @Test
    void savingFromAnEditorThatDoesNotSendOptionsLeavesThemAlone() throws Exception {
        JsonNode p = draft(unique("Bonnet"), false, opt("Black S", "Black", "S", "#1F1A1C"));
        String id = p.get("variants").get(0).get("id").asText();

        JsonNode saved = save(p, variant(id, "Black S renamed", "700", true, null, null, null, null, false));

        JsonNode v = saved.get("variants").get(0);
        assertThat(v.get("variantName").asText()).isEqualTo("Black S renamed");
        assertThat(v.get("sellingPrice").asDouble()).isEqualTo(700.0);
        assertThat(v.get("optionColor").asText()).isEqualTo("Black");
        assertThat(v.get("optionSize").asText()).isEqualTo("S");
        assertThat(v.get("swatchHex").asText()).isEqualTo("#1F1A1C");
        assertThat(list(saved, ProductOptionValue.Kind.COLOR)).containsExactly("Black|0|#1F1A1C|null");
    }

    @Test
    void theListKeepsItsOrderDropsWhatNoVariantHasAndAddsNewValuesAtTheEnd() throws Exception {
        JsonNode p = draft(unique("Gel Polish"), false,
                opt("Ruby", "Ruby", null, "#A3152A"), opt("Nude", "Nude", null, "#C8A28A"), opt("Coral", "Coral", null, "#F2705E"));
        String ruby = p.get("variants").get(0).get("id").asText();
        String coral = p.get("variants").get(2).get("id").asText();

        // Coral moved first, Nude removed, Blue added.
        JsonNode saved = save(p,
                variant(coral, "Coral", "650", true, "Coral", null, "#F2705E", null, true),
                variant(ruby, "Ruby", "650", true, "Ruby", null, "#A3152A", null, true),
                opt("Blue", "Blue", null, "#1F2A5A"));

        assertThat(list(saved, ProductOptionValue.Kind.COLOR))
                .containsExactly("Ruby|0|#A3152A|null", "Coral|1|#F2705E|null", "Blue|2|#1F2A5A|null");
        assertThat(list(saved, ProductOptionValue.Kind.SIZE)).isEmpty();
    }

    @Test
    void clearingAVariantsOptionsTakesItsColourOffTheListAndItsLabelOffTheVariant() throws Exception {
        JsonNode p = draft(unique("Lip Oil"), false, opt("Cherry", "Cherry", "5ml", "#C21E3A"));
        String id = p.get("variants").get(0).get("id").asText();

        JsonNode saved = save(p, variant(id, "Plain", "650", true, " ", "", "", "", true));

        JsonNode v = saved.get("variants").get(0);
        assertThat(v.get("optionColor").isNull()).isTrue();
        assertThat(v.get("optionSize").isNull()).isTrue();
        assertThat(v.get("swatchHex").isNull()).isTrue();
        assertThat(v.get("optionLabel").isNull()).isTrue();
        assertThat(v.get("sizePosition").isNull()).isTrue();
        assertThat(list(saved, ProductOptionValue.Kind.COLOR)).isEmpty();
        assertThat(list(saved, ProductOptionValue.Kind.SIZE)).isEmpty();
    }

    @Test
    void theProductPageOnlyOffersColoursThatCanBeBought() throws Exception {
        JsonNode p = draft(unique("Blush Stick"), true,
                opt("Rose", "Rose", null, "#E9A3B2"),
                variant(null, "Plum hidden", "650", false, "Plum", null, "#6E2A4D", null, true),
                variant(null, "Peach no price", "", true, "Peach", null, "#F6B48F", null, true));

        JsonNode detail = shop(get("/api/v1/catalog/variants/" + p.get("variants").get(0).get("id").asText()));
        assertThat(values(detail.get("colors"))).containsExactly("Rose");
        assertThat(detail.get("sizes")).isEmpty();
        // The hidden and unpriced ones still keep their place on the list for when they go on sale.
        assertThat(list(p, ProductOptionValue.Kind.COLOR)).hasSize(3);
    }

    @Test
    void aProductWithNoStoredListStillGetsItsPickerFromItsVariants() throws Exception {
        JsonNode p = draft(unique("Body Oil"), true,
                opt("50", null, "50ml", null), opt("100", null, "100ml", null));
        optionRows.deleteAll(optionRows.findByProductIdOrderByKindAscPositionAsc(UUID.fromString(p.get("id").asText())));

        JsonNode detail = shop(get("/api/v1/catalog/variants/" + p.get("variants").get(1).get("id").asText()));
        assertThat(values(detail.get("sizes"))).containsExactly("50ml", "100ml");
        assertThat(detail.get("colors")).isEmpty();
    }

    @Test
    void theCardCarriesTheBrand() throws Exception {
        String name = unique("Branded Serum");
        JsonNode p = draft(name, true, opt("30", null, "30ml", null));
        Brand brand = new Brand();
        brand.setName(unique("Lumi"));
        brand.setSlug(UUID.randomUUID().toString());
        brand = brands.save(brand);
        var product = products.findById(UUID.fromString(p.get("id").asText())).orElseThrow();
        product.setBrand(brand);
        products.save(product);

        assertThat(card(name, p.get("variants").get(0).get("id").asText()).get("brandName").asText())
                .isEqualTo(brand.getName());
    }

    @Test
    void aBadDotOrAMadeUpFamilyIsRefusedAndNothingIsSaved() throws Exception {
        String body = "{\"name\":\"" + unique("Bad") + "\",\"categoryIds\":[],\"published\":false,\"variants\":["
                + opt("Red", "Red", null, "#GGGGGG") + "]}";
        MvcResult badDot = call(post("/api/v1/products/draft"), body, true);
        assertThat(badDot.getResponse().getStatus()).isEqualTo(400);
        assertThat(badDot.getResponse().getContentAsString()).contains("#GGGGGG");

        String familyBody = "{\"name\":\"" + unique("Bad") + "\",\"categoryIds\":[],\"published\":false,\"variants\":["
                + variant(null, "Red", "650", true, "Red", null, "#9B111E", "Sparkly", true) + "]}";
        MvcResult badFamily = call(post("/api/v1/products/draft"), familyBody, true);
        assertThat(badFamily.getResponse().getStatus()).isEqualTo(400);
        assertThat(badFamily.getResponse().getContentAsString()).contains("Sparkly");
    }

    @Test
    void aDuplicateKeepsEveryVariantsColourAndSizeAndTheListsOrder() throws Exception {
        JsonNode p = draft(unique("Satin Bonnet"), false,
                opt("Wine L", "Wine", "L", "#6B1630"), opt("Wine S", "Wine", "S", "#6B1630"),
                opt("Black L", "Black", "L", "#1F1A1C"));

        JsonNode copy = ok(post("/api/v1/products/" + p.get("id").asText() + "/duplicate"), null);

        JsonNode v = copy.get("variants");
        assertThat(v.get(1).get("optionColor").asText()).isEqualTo("Wine");
        assertThat(v.get(1).get("optionSize").asText()).isEqualTo("S");
        assertThat(v.get(1).get("optionLabel").asText()).isEqualTo("Wine / S");
        assertThat(v.get(1).get("swatchHex").asText()).isEqualTo("#6B1630");
        assertThat(list(copy, ProductOptionValue.Kind.COLOR)).containsExactly("Wine|0|#6B1630|null", "Black|1|#1F1A1C|null");
        assertThat(list(copy, ProductOptionValue.Kind.SIZE)).containsExactly("L|0|null|null", "S|1|null|null");
    }
}
