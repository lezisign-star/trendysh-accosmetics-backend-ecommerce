package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Staff managing a product's colours and sizes as one list (owner, 2026-10-12: "are those colors manageable by the
 * staff and admins"), and changing several variants' prices at once. Unique names per run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductOptionManagementIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private ProductImageRepository images;

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

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult refused(MockHttpServletRequestBuilder request, String body, int status) throws Exception {
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return result;
    }

    private static String v(String name, String price, String color, String size, String hex) {
        return "{\"variantName\":\"" + name + "\",\"sellingPrice\":\"" + price + "\",\"isActive\":true,\"options\":{"
                + "\"color\":" + q(color) + ",\"size\":" + q(size) + ",\"swatchHex\":" + q(hex) + "}}";
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private JsonNode draft(String name, String... variantJson) throws Exception {
        return ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[" + String.join(",", variantJson) + "]}");
    }

    private JsonNode options(JsonNode product) throws Exception {
        return ok(get("/api/v1/products/" + product.get("id").asText() + "/options"), null);
    }

    private static JsonNode option(JsonNode list, String kind, String value) {
        return StreamSupport.stream(list.spliterator(), false)
                .filter(o -> o.get("kind").asText().equals(kind) && o.get("value").asText().equals(value))
                .findFirst().orElseThrow(() -> new AssertionError(kind + " " + value + " not in " + list));
    }

    private JsonNode variantsOf(JsonNode product) throws Exception {
        return ok(get("/api/v1/products/" + product.get("id").asText()), null).get("variants");
    }

    private String optionUrl(JsonNode product, JsonNode option) {
        return "/api/v1/products/" + product.get("id").asText() + "/options/" + option.get("id").asText();
    }

    @Test
    void renamingAColourRenamesEveryVariantWithItAndTheNamesTheGeneratorGaveButNotOnesStaffWrote() throws Exception {
        String name = unique("Velvet Matte");
        JsonNode p = draft(name,
                v(name + " – Rubby Red / S", "650", "Rubby Red", "S", "#9B111E"),
                v("Our favourite red", "700", "Rubby Red", "M", "#9B111E"),
                v(name + " – Nude / S", "650", "Nude", "S", "#C8A28A"));
        JsonNode red = option(options(p), "COLOR", "Rubby Red");
        assertThat(red.get("variantCount").asInt()).isEqualTo(2);

        JsonNode renamed = ok(patch(optionUrl(p, red)), "{\"value\":\"Ruby Red\"}");

        assertThat(renamed.get("value").asText()).isEqualTo("Ruby Red");
        assertThat(renamed.get("position").asInt()).as("keeps its place").isEqualTo(0);
        JsonNode vs = variantsOf(p);
        assertThat(vs.get(0).get("variantName").asText()).isEqualTo(name + " – Ruby Red / S");
        assertThat(vs.get(0).get("optionLabel").asText()).isEqualTo("Ruby Red / S");
        assertThat(vs.get(1).get("variantName").asText()).isEqualTo("Our favourite red");
        assertThat(vs.get(1).get("optionColor").asText()).isEqualTo("Ruby Red");
        assertThat(vs.get(1).get("optionLabel").asText()).isEqualTo("Ruby Red / M");
        assertThat(vs.get(2).get("variantName").asText()).isEqualTo(name + " – Nude / S");
    }

    @Test
    void renamingOntoAColourTheProductAlreadyHasIsRefusedRatherThanMerged() throws Exception {
        JsonNode p = draft(unique("Gel Polish"), v("a", "450", "Red", null, null), v("b", "450", "Cherry", null, null));
        JsonNode cherry = option(options(p), "COLOR", "Cherry");

        MvcResult r = refused(patch(optionUrl(p, cherry)), "{\"value\":\" red \"}", 409);

        assertThat(r.getResponse().getContentAsString()).contains("already has the colour");
        assertThat(option(options(p), "COLOR", "Cherry").get("variantCount").asInt()).isEqualTo(1);
    }

    @Test
    void aColoursDotAndFamilyChangeForAllItsSizesAtOnce() throws Exception {
        JsonNode p = draft(unique("Bonnet"),
                v("a", "550", "Wine", "S", "#6B1630"), v("b", "650", "Wine", "M", "#6B1630"),
                v("c", "550", "Black", "S", "#1F1A1C"));
        JsonNode wine = option(options(p), "COLOR", "Wine");
        assertThat(wine.get("effectiveColorFamily").asText()).isEqualTo("RED");

        JsonNode changed = ok(patch(optionUrl(p, wine)), "{\"swatchHex\":\"5a1028\",\"colorFamily\":\"purple\"}");

        assertThat(changed.get("swatchHex").asText()).isEqualTo("#5A1028");
        assertThat(changed.get("colorFamily").asText()).isEqualTo("PURPLE");
        JsonNode vs = variantsOf(p);
        for (int i = 0; i < 2; i++) {
            assertThat(vs.get(i).get("swatchHex").asText()).isEqualTo("#5A1028");
            assertThat(vs.get(i).get("colorFamily").asText()).isEqualTo("PURPLE");
        }
        assertThat(vs.get(2).get("swatchHex").asText()).as("another colour is untouched").isEqualTo("#1F1A1C");

        // "" clears the family again: back to what the dot looks like.
        assertThat(ok(patch(optionUrl(p, wine)), "{\"colorFamily\":\"\"}").get("effectiveColorFamily").asText())
                .isEqualTo("RED");
    }

    @Test
    void mistakesAreRefusedWithAReason() throws Exception {
        JsonNode p = draft(unique("Lip Oil"), v("a", "650", "Cherry", "5ml", "#C21E3A"));
        JsonNode list = options(p);
        JsonNode cherry = option(list, "COLOR", "Cherry");
        JsonNode fiveMl = option(list, "SIZE", "5ml");

        assertThat(refused(patch(optionUrl(p, cherry)), "{\"swatchHex\":\"#GG0000\"}", 400)
                .getResponse().getContentAsString()).contains("#GG0000");
        assertThat(refused(patch(optionUrl(p, cherry)), "{\"value\":\"  \"}", 400)
                .getResponse().getContentAsString()).contains("needs a name");
        assertThat(refused(patch(optionUrl(p, fiveMl)), "{\"swatchHex\":\"#000000\"}", 400)
                .getResponse().getContentAsString()).contains("A size has no colour dot");
        assertThat(refused(patch(optionUrl(p, cherry)), "{\"sizeLabelText\":\"5 ML\"}", 400)
                .getResponse().getContentAsString()).contains("belong to a size");
        refused(patch("/api/v1/products/" + p.get("id").asText() + "/options/" + UUID.randomUUID()), "{}", 404);
    }

    @Test
    void aSizeCanHaveLabelTextAndAPhotoButOnlyOneOfThisProductsPhotos() throws Exception {
        JsonNode p = draft(unique("Body Oil"), v("a", "900", null, "50ml", null), v("b", "1500", null, "100ml", null));
        JsonNode other = draft(unique("Other"), v("x", "100", null, null, null));
        JsonNode hundred = option(options(p), "SIZE", "100ml");

        JsonNode labelled = ok(patch(optionUrl(p, hundred)), "{\"sizeLabelText\":\"  100 ML  \"}");
        assertThat(labelled.get("sizeLabelText").asText()).isEqualTo("100 ML");

        String otherPhoto = photoOf(other).toString();
        refused(patch(optionUrl(p, hundred)), "{\"sizePhotoImageId\":\"" + otherPhoto + "\"}", 400);
        String ownPhoto = photoOf(p).toString();
        assertThat(ok(patch(optionUrl(p, hundred)), "{\"sizePhotoImageId\":\"" + ownPhoto + "\"}")
                .get("sizePhotoImageId").asText()).isEqualTo(ownPhoto);

        JsonNode cleared = ok(patch(optionUrl(p, hundred)), "{\"sizeLabelText\":\"\",\"sizePhotoImageId\":\"\"}");
        assertThat(cleared.get("sizeLabelText").isNull()).isTrue();
        assertThat(cleared.get("sizePhotoImageId").isNull()).isTrue();
    }

    private UUID photoOf(JsonNode product) {
        ProductImage photo = new ProductImage();
        photo.setProduct(products.findById(UUID.fromString(product.get("id").asText())).orElseThrow());
        photo.setVariant(variants.findById(UUID.fromString(product.get("variants").get(0).get("id").asText())).orElseThrow());
        photo.setUrl("https://res.cloudinary.com/test/" + UUID.randomUUID() + ".png");
        photo.setSource(ProductImage.ImageSource.UPLOAD);
        return images.saveAndFlush(photo).getId();
    }

    @Test
    void reorderingSizesMovesThemOnTheVariantsAndInTheShopAndAStaleListIsRefused() throws Exception {
        JsonNode p = draft(unique("Serum"),
                v("a", "900", null, "100ml", null), v("b", "600", null, "30ml", null), v("c", "750", null, "50ml", null));
        JsonNode list = options(p);
        String hundred = option(list, "SIZE", "100ml").get("id").asText();
        String thirty = option(list, "SIZE", "30ml").get("id").asText();
        String fifty = option(list, "SIZE", "50ml").get("id").asText();
        String orderUrl = "/api/v1/products/" + p.get("id").asText() + "/options/order";

        JsonNode reordered = ok(put(orderUrl), "{\"kind\":\"SIZE\",\"ids\":[\"" + thirty + "\",\"" + fifty + "\",\"" + hundred + "\"]}");

        assertThat(StreamSupport.stream(reordered.spliterator(), false).map(o -> o.get("value").asText()).toList())
                .containsExactly("30ml", "50ml", "100ml");
        JsonNode vs = variantsOf(p);
        assertThat(vs.get(0).get("sizePosition").asInt()).as("100ml is last now").isEqualTo(2);
        assertThat(vs.get(1).get("sizePosition").asInt()).isEqualTo(0);
        JsonNode detail = ok(get("/api/v1/catalog/variants/" + vs.get(0).get("id").asText()), null);
        assertThat(StreamSupport.stream(detail.get("sizes").spliterator(), false).map(o -> o.get("value").asText()).toList())
                .containsExactly("30ml", "50ml", "100ml");

        refused(put(orderUrl), "{\"kind\":\"SIZE\",\"ids\":[\"" + thirty + "\",\"" + fifty + "\"]}", 400);
        refused(put(orderUrl), "{\"kind\":\"SIZE\",\"ids\":[\"" + thirty + "\",\"" + thirty + "\",\"" + hundred + "\"]}", 400);
    }

    @Test
    void severalPricesCanBeSetRaisedOrChangedByAPercentageAllOrNothing() throws Exception {
        JsonNode p = draft(unique("Lipstick"),
                v("a", "1100", "Gold Shimmer", "Full", null), v("b", "650", "Gold Shimmer", "Mini", null),
                v("c", "1100", "Ruby", "Full", null));
        JsonNode vs = p.get("variants");
        String gold1 = vs.get(0).get("id").asText();
        String gold2 = vs.get(1).get("id").asText();
        String ruby = vs.get(2).get("id").asText();
        String url = "/api/v1/products/" + p.get("id").asText() + "/variants/prices";

        JsonNode raised = ok(patch(url), "{\"variantIds\":[\"" + gold1 + "\",\"" + gold2 + "\"],\"adjustBy\":150}").get("variants");
        assertThat(raised.get(0).get("sellingPrice").asDouble()).isEqualTo(1250.0);
        assertThat(raised.get(1).get("sellingPrice").asDouble()).isEqualTo(800.0);

        JsonNode percent = ok(patch(url), "{\"variantIds\":[\"" + ruby + "\"],\"adjustPercent\":7.5}").get("variants");
        assertThat(percent.get(0).get("sellingPrice").asDouble()).as("1100 x 1.075 = 1182.5, to the shilling").isEqualTo(1183.0);

        JsonNode set = ok(patch(url), "{\"variantIds\":[\"" + ruby + "\"],\"setPrice\":1000}").get("variants");
        assertThat(set.get(0).get("sellingPrice").asDouble()).isEqualTo(1000.0);

        // 800 - 900 < 0 for one of them: nothing changes, and the message names which.
        MvcResult tooLow = refused(patch(url), "{\"variantIds\":[\"" + gold1 + "\",\"" + gold2 + "\"],\"adjustBy\":-900}", 400);
        assertThat(tooLow.getResponse().getContentAsString()).contains("Gold Shimmer / Mini");
        assertThat(variantsOf(p).get(0).get("sellingPrice").asDouble()).isEqualTo(1250.0);

        refused(patch(url), "{\"variantIds\":[\"" + ruby + "\"],\"setPrice\":1000,\"adjustBy\":10}", 400);
        refused(patch(url), "{\"variantIds\":[],\"setPrice\":1000}", 400);
        JsonNode other = draft(unique("Other"), v("x", "100", null, null, null));
        refused(patch(url), "{\"variantIds\":[\"" + other.get("variants").get(0).get("id").asText() + "\"],\"setPrice\":5}", 400);
    }

    @Test
    void aSaleThatIsNoLongerBelowTheNewPriceIsClearedAndSaidSo() throws Exception {
        JsonNode p = draft(unique("Deal Lipstick"), v("a", "1000", "Ruby", null, null));
        String id = p.get("variants").get(0).get("id").asText();
        ok(put("/api/v1/products/" + p.get("id").asText() + "/variants/" + id + "/deal"),
                "{\"dealPrice\":900,\"dealEndsAt\":\"" + java.time.Instant.now().plusSeconds(86400) + "\"}");

        JsonNode result = ok(patch("/api/v1/products/" + p.get("id").asText() + "/variants/prices"),
                "{\"variantIds\":[\"" + id + "\"],\"setPrice\":850}");

        assertThat(result.get("variants").get(0).get("dealPrice").isNull()).isTrue();
        assertThat(result.get("warnings").get(0).asText()).contains("Ruby: its sale price was no longer below");
    }

    @Test
    void aVariantWithNoPriceCanBeGivenOneButNotRaised() throws Exception {
        JsonNode p = draft(unique("Draft Item"), v("a", "", "Red", null, null));
        String id = p.get("variants").get(0).get("id").asText();
        String url = "/api/v1/products/" + p.get("id").asText() + "/variants/prices";

        assertThat(refused(patch(url), "{\"variantIds\":[\"" + id + "\"],\"adjustBy\":100}", 400)
                .getResponse().getContentAsString()).contains("has no price yet");
        assertThat(ok(patch(url), "{\"variantIds\":[\"" + id + "\"],\"setPrice\":300}").get("variants").get(0).get("sellingPrice").asDouble())
                .isEqualTo(300.0);
    }

    @Test
    void anEditorOpenedBeforeAColourWasRenamedIsToldToReloadRatherThanSavingItsOlderVariants() throws Exception {
        String name = unique("Stale Editor");
        JsonNode p = draft(name, v(name + " – Rubby / S", "650", "Rubby", "S", "#9B111E"));
        String openedAt = p.get("updatedAt").asText();
        Thread.sleep(1100); // the editor compares to the second
        ok(patch(optionUrl(p, option(options(p), "COLOR", "Rubby"))), "{\"value\":\"Ruby\"}");

        // The page still has the old name; its Save must not put it back.
        refused(put("/api/v1/products/" + p.get("id").asText() + "/full"), "{\"expectedUpdatedAt\":\"" + openedAt
                + "\",\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,\"variants\":[{\"id\":\""
                + p.get("variants").get(0).get("id").asText() + "\",\"variantName\":\"" + name
                + " – Rubby / S\",\"sellingPrice\":\"650\",\"isActive\":true}]}", 409);
        assertThat(variantsOf(p).get(0).get("variantName").asText()).isEqualTo(name + " – Ruby / S");
    }

    @Test
    void theListShowsColoursThenSizesInOrderWithHowManyVariantsHaveEach() throws Exception {
        JsonNode p = draft(unique("Bonnet"),
                v("a", "550", "Black", "S", "#1F1A1C"), v("b", "650", "Black", "M", "#1F1A1C"),
                v("c", "650", "Wine", "M", "#6B1630"));
        List<String> rows = StreamSupport.stream(options(p).spliterator(), false)
                .map(o -> o.get("kind").asText() + ":" + o.get("value").asText() + ":" + o.get("variantCount").asInt())
                .toList();
        assertThat(rows).containsExactly("COLOR:Black:2", "COLOR:Wine:1", "SIZE:S:1", "SIZE:M:2");
    }
}
