package com.nbh.backend.kits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A shopper swapping the colour or size of a product inside a kit (owner, 2026-10-19): the kit's page offers the options,
 * the cart prices the picks (package price + quantity x (picked price - default price)), counts them against the shelf of
 * the variant picked, keeps the same kit with different picks as different lines, and checkout reserves and names exactly
 * what was picked. Same Spring context as {@link KitIntegrationTest} (identical annotations), so no extra connection pool.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KitChoicesIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private StockReservationRepository reservationRepository;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    // ---- helpers (the same ones KitIntegrationTest has, kept private there) ----

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private MvcResult cartRaw(MockHttpServletRequestBuilder request, String body, String sessionId) throws Exception {
        request.header("X-Cart-Session", sessionId);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode cartOk(MockHttpServletRequestBuilder request, String body, String sessionId) throws Exception {
        MvcResult result = cartRaw(request, body, sessionId);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String newCategory() {
        Category c = new Category();
        c.setSlug("kit-choice-category-" + UUID.randomUUID().toString().substring(0, 8));
        c.setName(unique("Kit Choice Category"));
        return categoryRepository.save(c).getId().toString();
    }

    private String newProfession() throws Exception {
        return ok(post("/api/v1/admin/professions"),
                "{\"label\":\"" + unique("Lash Tech") + "\",\"slug\":\"lash-tech-" + UUID.randomUUID().toString().substring(0, 8) + "\"}")
                .get("id").asText();
    }

    /** A published product with these variants (name, price, colour); returns variant ids by name. */
    private Map<String, String> newProduct(String name, String[]... variants) throws Exception {
        StringBuilder list = new StringBuilder();
        for (String[] v : variants) {
            if (!list.isEmpty()) list.append(",");
            list.append("{\"variantName\":\"").append(v[0]).append("\",\"sellingPrice\":\"").append(v[1])
                    .append("\",\"isActive\":true,\"options\":{\"color\":\"").append(v[0])
                    .append("\",\"size\":null,\"swatchHex\":null,\"colorFamily\":null}}");
        }
        JsonNode product = ok(post("/api/v1/products/draft"),
                "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,\"variants\":[" + list + "]}");
        ok(post("/api/v1/products/" + product.get("id").asText() + "/publish"), null);
        Map<String, String> ids = new HashMap<>();
        for (JsonNode variant : product.get("variants")) {
            ids.put(variant.get("variantName").asText(), variant.get("id").asText());
        }
        return ids;
    }

    private String newSupplier() throws Exception {
        return ok(post("/api/v1/admin/suppliers"),
                "{\"name\":\"" + unique("Kit Choice Supplier") + "\",\"phone\":\"0700000000\"}").get("id").asText();
    }

    private void receiveStock(Map<String, BigDecimal> qtyByVariantId) throws Exception {
        StringBuilder lines = new StringBuilder();
        for (var e : qtyByVariantId.entrySet()) {
            if (!lines.isEmpty()) lines.append(",");
            lines.append("{\"variantId\":\"").append(e.getKey()).append("\",\"qty\":\"").append(e.getValue())
                    .append("\",\"unitCost\":\"100\"}");
        }
        JsonNode po = ok(post("/api/v1/admin/purchase-orders"),
                "{\"supplierId\":\"" + newSupplier() + "\",\"lines\":[" + lines + "]}");
        String poId = po.get("id").asText();
        ok(post("/api/v1/admin/purchase-orders/" + poId + "/send"), null);
        StringBuilder receiveLines = new StringBuilder();
        for (JsonNode line : po.get("lines")) {
            if (!receiveLines.isEmpty()) receiveLines.append(",");
            receiveLines.append("{\"poLineId\":\"").append(line.get("id").asText()).append("\",\"qty\":\"")
                    .append(qtyByVariantId.get(line.get("variantId").asText())).append("\"}");
        }
        ok(post("/api/v1/admin/purchase-orders/" + poId + "/receive"), "{\"lines\":[" + receiveLines + "]}");
    }

    private BigDecimal shopOnHand(String variantId) throws Exception {
        for (JsonNode b : ok(get("/api/v1/stock/balance?variantId=" + variantId), null)) {
            if ("SHOP".equals(b.get("account").asText())) return b.get("onHandQty").decimalValue();
        }
        return BigDecimal.ZERO;
    }

    private String checkoutBody() {
        String phone = "2547" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        return "{\"customerName\":\"Test Customer\",\"guestEmail\":\"picks-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com\",\"guestPhone\":\"" + phone + "\",\"fulfilmentType\":\"PICKUP\"}";
    }

    private static String kitBody(String name, String professionId, String categoryId, String price, String... components) {
        return "{\"name\":\"" + name + "\",\"professionId\":\"" + professionId + "\",\"categoryId\":\"" + categoryId
                + "\",\"description\":\"For a technician\",\"finalPrice\":\"" + price + "\",\"components\":["
                + String.join(",", components) + "]}";
    }

    private static String component(String variantId, String qty, boolean canChange) {
        return "{\"variantId\":\"" + variantId + "\",\"qty\":\"" + qty + "\",\"customerCanChange\":" + canChange + "}";
    }

    private String addBody(String kitVariantId, int qty, Map<String, String> picks) {
        StringBuilder sb = new StringBuilder("{\"variantId\":\"" + kitVariantId + "\",\"qty\":" + qty);
        if (picks != null) {
            sb.append(",\"kitChoices\":{");
            boolean first = true;
            for (var e : picks.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
                first = false;
            }
            sb.append("}");
        }
        return sb.append("}").toString();
    }

    /** Lashes in two colours (2 of them in the kit) and a plain cleanser; the kit costs 1000 against 1300 separately. */
    private record Fixture(String kitId, String kitVariantId, String kitSlug, String redId, String pinkId, String budgetId, String cleanserId,
                           String lashProductId) {}

    private Fixture fixture(boolean lashesCanChange, int pinkStock) throws Exception {
        Map<String, String> lashes = newProduct(unique("Choice Lashes"), new String[]{"Ruby Red", "500"},
                new String[]{"Rose Pink", "650"}, new String[]{"Budget Black", "400"});
        Map<String, String> cleanser = newProduct(unique("Choice Cleanser"), new String[]{"Default", "300"});
        String red = lashes.get("Ruby Red"), pink = lashes.get("Rose Pink"), budget = lashes.get("Budget Black");
        String cleanserId = cleanser.get("Default");
        receiveStock(Map.of(red, new BigDecimal("10"), pink, new BigDecimal(pinkStock), budget, new BigDecimal("10"),
                cleanserId, new BigDecimal("10")));
        JsonNode kit = ok(post("/api/v1/admin/kits"), kitBody(unique("Choice Kit"), newProfession(), newCategory(), "1000",
                component(red, "2", lashesCanChange), component(cleanserId, "1", true)));
        String slug = jdbc.queryForObject("SELECT slug FROM product WHERE id = ?::uuid", String.class, kit.get("productId").asText());
        return new Fixture(kit.get("id").asText(), kit.get("variantId").asText(), slug, red, pink, budget, cleanserId, null);
    }

    private JsonNode line(JsonNode cart, int index) {
        return cart.get("items").get(index);
    }

    // ---- the kit's page offers the options ----

    @Test
    void theKitPageOffersTheColoursOfAChangeableProductWithWhatEachOneAddsToThePrice() throws Exception {
        Fixture f = fixture(true, 10);

        MvcResult result = mvc.perform(get("/api/v1/catalog/kits/" + f.kitSlug())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode page = json.readTree(result.getResponse().getContentAsString());

        JsonNode lashes = null, cleanser = null;
        for (JsonNode item : page.get("items")) {
            if (item.get("variantId").asText().equals(f.redId())) lashes = item;
            if (item.get("variantId").asText().equals(f.cleanserId())) cleanser = item;
        }
        assertThat(lashes).isNotNull();
        assertThat(lashes.get("canChange").asBoolean()).isTrue();
        assertThat(lashes.get("choices")).hasSize(3);
        Map<String, JsonNode> byId = new HashMap<>();
        lashes.get("choices").forEach(c -> byId.put(c.get("variantId").asText(), c));
        assertThat(byId.get(f.redId()).get("isDefault").asBoolean()).isTrue();
        assertThat(byId.get(f.redId()).get("priceDelta").decimalValue()).isEqualByComparingTo("0");
        // two lashes per kit: 2 x (650 - 500) more, and 2 x (400 - 500) less
        assertThat(byId.get(f.pinkId()).get("priceDelta").decimalValue()).isEqualByComparingTo("300");
        assertThat(byId.get(f.budgetId()).get("priceDelta").decimalValue()).isEqualByComparingTo("-200");
        assertThat(byId.get(f.pinkId()).get("inStock").asBoolean()).isTrue();
        // a one-option product has nothing to choose
        assertThat(cleanser).isNotNull();
        assertThat(cleanser.get("canChange").asBoolean()).isFalse();
        assertThat(cleanser.get("choices")).isEmpty();
    }

    @Test
    void aProductStaffSwitchedOffIsNotOfferedAndAnOptionOutOfStockIsMarked() throws Exception {
        Fixture off = fixture(false, 10);
        JsonNode pageOff = json.readTree(mvc.perform(get("/api/v1/catalog/kits/" + off.kitSlug())).andReturn()
                .getResponse().getContentAsString());
        for (JsonNode item : pageOff.get("items")) {
            assertThat(item.get("canChange").asBoolean()).as(item.get("name").asText()).isFalse();
        }

        // 1 pink on the shelf, 2 lashes in the kit: that option cannot make up a kit
        Fixture low = fixture(true, 1);
        JsonNode pageLow = json.readTree(mvc.perform(get("/api/v1/catalog/kits/" + low.kitSlug())).andReturn()
                .getResponse().getContentAsString());
        boolean checked = false;
        for (JsonNode item : pageLow.get("items")) {
            for (JsonNode choice : item.get("choices")) {
                if (choice.get("variantId").asText().equals(low.pinkId())) {
                    assertThat(choice.get("inStock").asBoolean()).isFalse();
                    checked = true;
                }
            }
        }
        assertThat(checked).isTrue();
    }

    // ---- the cart prices and separates the picks ----

    @Test
    void aPickChangesTheKitsPriceByQuantityTimesTheDifferenceAndMakesItsOwnLine() throws Exception {
        Fixture f = fixture(true, 10);
        String session = "picks-" + UUID.randomUUID();

        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, null), session);
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), session);
        JsonNode cart = cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), session);

        assertThat(cart.get("items")).as("the kit as it comes and the kit with pink are two lines").hasSize(2);
        JsonNode plain = null, pink = null;
        for (JsonNode item : cart.get("items")) {
            if (item.get("kitChoices").isEmpty()) plain = item; else pink = item;
        }
        assertThat(plain.get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(plain.get("qty").decimalValue()).isEqualByComparingTo("1");
        assertThat(pink.get("unitPrice").decimalValue()).as("1000 + 2 x (650 - 500)").isEqualByComparingTo("1300");
        assertThat(pink.get("qty").decimalValue()).as("the same picks add to the same line").isEqualByComparingTo("2");
        assertThat(cart.get("subtotal").decimalValue()).isEqualByComparingTo("3600");

        // what is inside the picked line, with the pick applied
        boolean sawPink = false;
        for (JsonNode part : pink.get("kitItems")) {
            if (part.get("defaultVariantId").asText().equals(f.redId())) {
                assertThat(part.get("variantId").asText()).isEqualTo(f.pinkId());
                assertThat(part.get("changed").asBoolean()).isTrue();
                assertThat(part.get("qtyPerKit").decimalValue()).isEqualByComparingTo("2");
                sawPink = true;
            } else {
                assertThat(part.get("changed").asBoolean()).isFalse();
            }
        }
        assertThat(sawPink).isTrue();
        assertThat(pink.get("kitChoices").get(f.redId()).asText()).isEqualTo(f.pinkId());
    }

    @Test
    void aCheaperPickLowersThePriceAndPickingTheDefaultChangesNothing() throws Exception {
        Fixture f = fixture(true, 10);
        String session = "cheaper-" + UUID.randomUUID();

        JsonNode cheaper = cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.budgetId())), session);
        assertThat(line(cheaper, 0).get("unitPrice").decimalValue()).as("1000 + 2 x (400 - 500)").isEqualByComparingTo("800");

        // "picking" what the kit already has is the kit as it comes: it joins the plain line, not a new one
        String second = "default-pick-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, null), second);
        JsonNode cart = cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.redId())), second);
        assertThat(cart.get("items")).hasSize(1);
        assertThat(line(cart, 0).get("qty").decimalValue()).isEqualByComparingTo("2");
        assertThat(line(cart, 0).get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(line(cart, 0).get("kitChoices")).isEmpty();
    }

    @Test
    void picksThatDoNotBelongAreRefusedWithAMessageTheShopperCanActOn() throws Exception {
        Fixture f = fixture(true, 10);
        Fixture other = fixture(true, 10); // another kit's lashes are not this kit's product
        String session = "refused-" + UUID.randomUUID();

        // a colour of a different product
        MvcResult wrongProduct = cartRaw(post("/api/v1/cart/items"),
                addBody(f.kitVariantId(), 1, Map.of(f.redId(), other.pinkId())), session);
        assertThat(wrongProduct.getResponse().getStatus()).isEqualTo(422);
        assertThat(wrongProduct.getResponse().getContentAsString()).contains("Please pick a colour or size of");

        // a product that is not in the kit
        MvcResult notInKit = cartRaw(post("/api/v1/cart/items"),
                addBody(f.kitVariantId(), 1, Map.of(other.redId(), f.pinkId())), session);
        assertThat(notInKit.getResponse().getStatus()).isEqualTo(422);

        // picks on something that is not a kit
        MvcResult notAKit = cartRaw(post("/api/v1/cart/items"),
                addBody(f.redId(), 1, Map.of(f.redId(), f.pinkId())), session);
        assertThat(notAKit.getResponse().getStatus()).isEqualTo(422);
        assertThat(notAKit.getResponse().getContentAsString()).contains("Only a kit has products inside to change");

        // a product staff did not allow to be changed
        Fixture locked = fixture(false, 10);
        MvcResult notAllowed = cartRaw(post("/api/v1/cart/items"),
                addBody(locked.kitVariantId(), 1, Map.of(locked.redId(), locked.pinkId())), session);
        assertThat(notAllowed.getResponse().getStatus()).isEqualTo(422);
        assertThat(notAllowed.getResponse().getContentAsString()).contains("can't be changed in this kit");

        // an option that can no longer be bought
        jdbc.update("UPDATE product_variant SET is_active = false WHERE id = ?::uuid", f.budgetId());
        MvcResult gone = cartRaw(post("/api/v1/cart/items"),
                addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.budgetId())), session);
        assertThat(gone.getResponse().getStatus()).isEqualTo(422);
        assertThat(gone.getResponse().getContentAsString()).contains("can't be ordered right now");

        // more picks than the kit has products: refused before anything is looked up
        MvcResult tooMany = cartRaw(post("/api/v1/cart/items"),
                addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId(), f.cleanserId(), f.cleanserId(),
                        UUID.randomUUID().toString(), UUID.randomUUID().toString())), session);
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(422);

        // nothing was added by any of them
        JsonNode cart = cartOk(get("/api/v1/cart"), null, session);
        assertThat(cart.get("items")).isEmpty();
    }

    @Test
    void thePickedVariantsShelfDecidesHowManyKitsCanBeAdded() throws Exception {
        Fixture f = fixture(true, 3); // 3 pink on the shelf, 2 per kit: one kit
        String session = "shelf-" + UUID.randomUUID();

        MvcResult two = cartRaw(post("/api/v1/cart/items"),
                addBody(f.kitVariantId(), 2, Map.of(f.redId(), f.pinkId())), session);
        assertThat(two.getResponse().getStatus()).as("two kits would need 4 pink").isEqualTo(400);
        assertThat(two.getResponse().getContentAsString()).contains("Only 1");

        JsonNode one = cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), session);
        assertThat(line(one, 0).get("stockLeft").decimalValue()).isEqualByComparingTo("1");
        // the kit as it comes still has red in plenty
        JsonNode plain = cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 2, null), session);
        assertThat(plain.get("items")).hasSize(2);
    }

    /**
     * Found by the kits audit (2026-10-23): when staff switched swapping off, an old line quietly went back to the colour
     * the kit comes with, and with colours of the same price nothing at all told the shopper. A pick that no longer applies
     * now stops the line instead, with a message that says to choose again.
     */
    @Test
    void switchingSwappingOffLaterStopsAnOldLineInsteadOfQuietlyPackingAnotherColour() throws Exception {
        Fixture f = fixture(true, 10);
        String session = "switched-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), session);

        JsonNode kit = ok(get("/api/v1/admin/kits/" + f.kitId()), null);
        ok(put("/api/v1/admin/kits/" + f.kitId()), kitBody(kit.get("name").asText(), kit.get("professionId").asText(),
                kit.get("categoryId").asText(), "1000", component(f.redId(), "2", false), component(f.cleanserId(), "1", true)));

        JsonNode cart = cartOk(get("/api/v1/cart"), null, session);
        assertThat(line(cart, 0).get("available").asBoolean()).as("the line says it cannot be ordered as it is").isFalse();
        MvcResult refused = cartRaw(post("/api/v1/checkout"), checkoutBody(), session);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("not on offer any more").contains("choose again");

        // taking the kit out and adding it again as it comes works, at the package price
        String lineId = line(cart, 0).get("id").asText();
        cartOk(delete("/api/v1/cart/items/" + lineId), null, session);
        JsonNode again = cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, null), session);
        assertThat(line(again, 0).get("available").asBoolean()).isTrue();
        assertThat(line(again, 0).get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(cartRaw(post("/api/v1/checkout"), checkoutBody(), session).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aDefaultTakenOffSaleDoesNotTakeAShoppersPickedColourOutOfTheKit() throws Exception {
        Fixture f = fixture(true, 10);
        String session = "default-gone-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), session);

        jdbc.update("UPDATE product_variant SET is_active = false WHERE id = ?::uuid", f.redId());

        JsonNode cart = cartOk(get("/api/v1/cart"), null, session);
        assertThat(line(cart, 0).get("available").asBoolean()).as("pink is still on sale").isTrue();
        assertThat(line(cart, 0).get("unitPrice").decimalValue()).as("1000 + 2 x (650 - 500), as they were shown")
                .isEqualByComparingTo("1300");
        assertThat(line(cart, 0).get("kitItems")).as("the lashes are still in the kit, in pink").hasSize(2);
        JsonNode order = cartOk(post("/api/v1/checkout"), checkoutBody(), session).get("order");
        JsonNode detail = ok(get("/api/v1/orders/" + order.get("id").asText()), null);
        List<String> packed = new java.util.ArrayList<>();
        detail.get("items").get(0).get("components").forEach(c -> packed.add(c.get("variantId").asText()));
        assertThat(packed).contains(f.pinkId(), f.cleanserId()).doesNotContain(f.redId());
    }

    @Test
    void aKitsDefaultChangedByStaffStillGivesTheShopperTheColourTheyPicked() throws Exception {
        Fixture f = fixture(true, 10);
        String session = "default-changed-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), session);

        // staff make black the colour the kit comes with, instead of red
        JsonNode kit = ok(get("/api/v1/admin/kits/" + f.kitId()), null);
        ok(put("/api/v1/admin/kits/" + f.kitId()), kitBody(kit.get("name").asText(), kit.get("professionId").asText(),
                kit.get("categoryId").asText(), "1000", component(f.budgetId(), "2", true), component(f.cleanserId(), "1", true)));

        JsonNode cart = cartOk(get("/api/v1/cart"), null, session);
        assertThat(line(cart, 0).get("available").asBoolean()).isTrue();
        assertThat(line(cart, 0).get("unitPrice").decimalValue()).as("1000 + 2 x (650 - 400): pink is still what they get")
                .isEqualByComparingTo("1500");
        JsonNode order = cartOk(post("/api/v1/checkout"), checkoutBody(), session).get("order");
        JsonNode detail = ok(get("/api/v1/orders/" + order.get("id").asText()), null);
        assertThat(detail.get("subtotal").decimalValue()).isEqualByComparingTo("1500");
        List<String> packed = new java.util.ArrayList<>();
        detail.get("items").get(0).get("components").forEach(c -> packed.add(c.get("variantId").asText()));
        assertThat(packed).contains(f.pinkId()).doesNotContain(f.redId(), f.budgetId());
    }

    @Test
    void twoProductsPickedToTheSameColourShareItsShelf() throws Exception {
        // A kit can hold two shades of one product (red and pink lashes, one of each); picking pink for the red one makes
        // both pink, so one kit needs two pink. With 3 on the shelf that is one kit, not three.
        Map<String, String> lashes = newProduct(unique("Shared Shelf Lashes"), new String[]{"Ruby Red", "500"},
                new String[]{"Rose Pink", "650"});
        String red = lashes.get("Ruby Red"), pink = lashes.get("Rose Pink");
        receiveStock(Map.of(red, new BigDecimal("10"), pink, new BigDecimal("3")));
        JsonNode kit = ok(post("/api/v1/admin/kits"), kitBody(unique("Shared Shelf Kit"), newProfession(), newCategory(), "1000",
                component(red, "1", true), component(pink, "1", true)));
        String kitVariantId = kit.get("variantId").asText();
        String session = "shared-shelf-" + UUID.randomUUID();

        MvcResult two = cartRaw(post("/api/v1/cart/items"), addBody(kitVariantId, 2, Map.of(red, pink)), session);
        assertThat(two.getResponse().getStatus()).as("two kits would need 4 pink").isEqualTo(400);
        assertThat(two.getResponse().getContentAsString()).contains("Only 1");

        JsonNode one = cartOk(post("/api/v1/cart/items"), addBody(kitVariantId, 1, Map.of(red, pink)), session);
        assertThat(line(one, 0).get("stockLeft").decimalValue()).isEqualByComparingTo("1");
    }

    @Test
    void aKitCannotHoldPartOfAPiece() throws Exception {
        Map<String, String> oil = newProduct(unique("Whole Piece Oil"), new String[]{"Default", "450"});
        JsonNode body = json.readTree(kitBody(unique("Half Kit"), newProfession(), newCategory(), "100",
                component(oil.get("Default"), "1.5", true)));
        MvcResult refused = mvc.perform(post("/api/v1/admin/kits").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("whole number");
    }

    @Test
    void aKitCanBeSavedAgainWithTheSameProductsAndTheSwitchIsKept() throws Exception {
        // Found while building the swap switch: re-saving a kit whose products had not changed (a new price or
        // description) failed with a 409, because the products were re-inserted before the old rows were deleted.
        Fixture f = fixture(true, 10);
        JsonNode kit = ok(get("/api/v1/admin/kits/" + f.kitId()), null);

        JsonNode saved = ok(put("/api/v1/admin/kits/" + f.kitId()), kitBody("Renamed " + kit.get("name").asText(),
                kit.get("professionId").asText(), kit.get("categoryId").asText(), "900",
                component(f.cleanserId(), "1", true), component(f.redId(), "2", false)));

        assertThat(saved.get("packagePrice").decimalValue()).isEqualByComparingTo("900");
        assertThat(saved.get("components")).hasSize(2);
        // the order staff gave them is the order they come back in, and the switch is what was sent
        assertThat(saved.get("components").get(0).get("variantId").asText()).isEqualTo(f.cleanserId());
        assertThat(saved.get("components").get(1).get("variantId").asText()).isEqualTo(f.redId());
        assertThat(saved.get("components").get(1).get("customerCanChange").asBoolean()).isFalse();
        assertThat(saved.get("components").get(0).get("customerCanChange").asBoolean()).isTrue();

        // and a product can still be taken out and put back
        JsonNode fewer = ok(put("/api/v1/admin/kits/" + f.kitId()), kitBody("Renamed again", kit.get("professionId").asText(),
                kit.get("categoryId").asText(), "300", component(f.cleanserId(), "1", true)));
        assertThat(fewer.get("components")).hasSize(1);
        JsonNode back = ok(put("/api/v1/admin/kits/" + f.kitId()), kitBody("Renamed again", kit.get("professionId").asText(),
                kit.get("categoryId").asText(), "900", component(f.redId(), "2", true), component(f.cleanserId(), "1", true)));
        assertThat(back.get("components")).hasSize(2);
    }

    // ---- checkout reserves and names what was picked ----

    @Test
    void checkoutReservesAndNamesThePickedVariantAndFulfilmentTakesItsStock() throws Exception {
        Fixture f = fixture(true, 10);
        String session = "checkout-picks-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 2, Map.of(f.redId(), f.pinkId())), session);

        JsonNode result = cartOk(post("/api/v1/checkout"), checkoutBody(), session);
        String orderId = result.get("order").get("id").asText();

        JsonNode detail = ok(get("/api/v1/orders/" + orderId), null);
        assertThat(detail.get("items")).hasSize(1);
        JsonNode kitLine = detail.get("items").get(0);
        assertThat(kitLine.get("unitPrice").decimalValue()).isEqualByComparingTo("1300");
        assertThat(kitLine.get("lineTotal").decimalValue()).isEqualByComparingTo("2600");
        assertThat(detail.get("subtotal").decimalValue()).isEqualByComparingTo("2600");
        assertThat(kitLine.get("components")).hasSize(2);
        boolean pinkListed = false;
        for (JsonNode component : kitLine.get("components")) {
            String variant = component.get("variantId").asText();
            assertThat(variant).as("the default colour is not on the order").isNotEqualTo(f.redId());
            if (variant.equals(f.pinkId())) {
                assertThat(component.get("qty").decimalValue()).as("2 per kit x 2 kits").isEqualByComparingTo("4");
                assertThat(component.get("unitPrice").decimalValue()).isEqualByComparingTo("650");
                pinkListed = true;
            }
        }
        assertThat(pinkListed).isTrue();

        List<StockReservation> reservations = reservationRepository.findByReferenceTypeAndReferenceId("CUSTOMER_ORDER", UUID.fromString(orderId));
        assertThat(reservations).hasSize(2);

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);
        ok(post("/api/v1/orders/" + orderId + "/fulfil"), null);

        assertThat(shopOnHand(f.pinkId())).as("the picked colour was packed").isEqualByComparingTo("6");
        assertThat(shopOnHand(f.redId())).as("the default colour was never touched").isEqualByComparingTo("10");
        assertThat(shopOnHand(f.cleanserId())).isEqualByComparingTo("8");
    }

    @Test
    void aPickTakenOffSaleAfterItWasAddedBlocksTheLineAndCheckout() throws Exception {
        Fixture f = fixture(true, 10);
        String session = "gone-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), session);

        jdbc.update("UPDATE product_variant SET is_active = false WHERE id = ?::uuid", f.pinkId());

        JsonNode cart = cartOk(get("/api/v1/cart"), null, session);
        assertThat(line(cart, 0).get("available").asBoolean()).as("the line says it cannot be bought").isFalse();
        MvcResult refused = cartRaw(post("/api/v1/checkout"), checkoutBody(), session);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("is no longer available");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE order_no IS NOT NULL AND id IN"
                + " (SELECT order_id FROM customer_order_item WHERE variant_id = ?::uuid)", Integer.class, f.pinkId())).isZero();
    }

    @Test
    void aPricesMovePickedVariantIsRepricedLikeEverythingInTheCart() throws Exception {
        Fixture f = fixture(true, 10);
        String session = "reprice-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), session);

        jdbc.update("UPDATE product_variant SET selling_price = 700 WHERE id = ?::uuid", f.pinkId());

        JsonNode cart = cartOk(get("/api/v1/cart"), null, session);
        assertThat(line(cart, 0).get("unitPrice").decimalValue()).as("1000 + 2 x (700 - 500)").isEqualByComparingTo("1400");
        assertThat(line(cart, 0).get("previousUnitPrice").decimalValue()).isEqualByComparingTo("1300");
    }
}
