package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.dto.OrderItemDto;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * An order line remembers what was bought (V62). The customer picked "Velvet Matte Lipstick – Ruby Red / 50ml", SKU
 * VML-…; staff rename the product, change the option and re-number the SKU the next week; the order, its detail
 * screen and the receipt email must all still say what the customer ordered. An order placed before the snapshot
 * columns existed (nulls) falls back to the live variant, as it always did.
 *
 * <p>Same annotations as {@link OrderEmailsIntegrationTest} on purpose: the Spring context is shared, not rebuilt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderLineSnapshotIntegrationTest {

    private static final String PHOTO = "https://res.cloudinary.com/demo/image/upload/v1/products/ruby-red.jpg";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustomerOrderRepository orders;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private EmailSender emailSender;

    private String staffToken;

    @BeforeEach
    void setUp() {
        reset(emailSender);
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A product with one stocked option that looks like a generated variant: colour, size, dot, SKU and a photo. */
    private record Bought(String productId, UUID variantId, String productName, String sku) {}

    private Bought newGeneratedLookingVariant() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productName = "Velvet Matte Lipstick " + suffix;
        String body = "{\"name\":\"" + productName + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        UUID variantId = UUID.fromString(product.get("variants").get(0).get("id").asText());
        okStaff(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":500,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));

        String sku = "VML-" + suffix.toUpperCase();
        jdbc.update("UPDATE product_variant SET sku = ?, variant_name = ?, option_label = ?, option_color = ?,"
                        + " option_size = ?, swatch_hex = ? WHERE id = ?",
                sku, productName + " – Ruby Red / 50ml", "Ruby Red / 50ml", "Ruby Red", "50ml", "#9B111E", variantId);
        jdbc.update("INSERT INTO product_image (id, product_id, variant_id, url, source, is_primary, sort_order)"
                + " VALUES (?, ?, ?, ?, 'UPLOAD', true, 0)", UUID.randomUUID(), UUID.fromString(productId), variantId, PHOTO);
        return new Bought(productId, variantId, productName, sku);
    }

    private record Placed(String orderId, String orderNo, String reference, String email, JsonNode checkoutResponse) {}

    private Placed place(Bought bought) throws Exception {
        String session = UUID.randomUUID().toString();
        MvcResult added = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + bought.variantId() + "\",\"qty\":1}")).andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
        String email = TestContacts.email();
        String body = """
                {"customerName":"Njeri Christine","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"PICKUP",
                 "deliveryAddress":"Simara Mall — Shop GS 07"}
                """.formatted(email, TestContacts.phone());
        MvcResult placed = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        String response = placed.getResponse().getContentAsString();
        return new Placed(JsonPath.read(response, "$.order.id"), JsonPath.read(response, "$.order.orderNo"),
                JsonPath.read(response, "$.paymentInstructions.reference"), email, json.readTree(response));
    }

    /** Staff rename everything about the variant, change its colour and size, drop its photo and re-number the SKU. */
    private void renameEverything(Bought bought) {
        jdbc.update("UPDATE product SET name = ? WHERE id = ?", "Velvet Lip Colour " + bought.sku(),
                UUID.fromString(bought.productId()));
        jdbc.update("UPDATE product_variant SET sku = ?, variant_name = ?, option_label = ?, option_color = ?,"
                        + " option_size = ?, swatch_hex = ? WHERE id = ?",
                "RENAMED-" + bought.sku(), "Velvet Lip Colour – Crimson / 30ml", "Crimson / 30ml", "Crimson", "30ml",
                "#DC143C", bought.variantId());
        jdbc.update("DELETE FROM product_image WHERE variant_id = ?", bought.variantId());
    }

    private void claimAndApprove(Placed order) throws Exception {
        String code = "QR" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
        MvcResult claim = mvc.perform(post("/api/v1/payments/pay/" + order.orderNo() + "/claim").param("ref", order.reference())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionCode\":\"" + code + "\",\"amount\":1000}")).andReturn();
        assertThat(claim.getResponse().getStatus()).isEqualTo(200);
        String claims = mvc.perform(get("/api/v1/admin/payments/claims").header("Authorization", "Bearer " + staffToken))
                .andReturn().getResponse().getContentAsString();
        String claimId = JsonPath.<List<String>>read(claims, "$[?(@.orderNo=='" + order.orderNo() + "')].id").get(0);
        MvcResult approved = mvc.perform(post("/api/v1/admin/payments/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + staffToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"seen on the statement\"}")).andReturn();
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
    }

    /** The text of every email sent to one address, in order. */
    private List<String> textsSentTo(String email) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender, org.mockito.Mockito.atLeast(0))
                .send(eq(email), org.mockito.ArgumentMatchers.anyString(), text.capture(), org.mockito.ArgumentMatchers.anyString());
        return text.getAllValues();
    }

    private OrderItemDto customerLine(String orderId) {
        return tx.execute(status -> OrderDto.from(orders.findById(UUID.fromString(orderId)).orElseThrow()).items().get(0));
    }

    @Test
    void renamingTheVariantAfterCheckoutChangesNothingOnTheOrderItsDetailOrTheReceipt() throws Exception {
        Bought bought = newGeneratedLookingVariant();
        Placed order = place(bought);

        // What the shopper is told the moment the order is placed.
        JsonNode placedLine = order.checkoutResponse().get("order").get("items").get(0);
        assertThat(placedLine.get("sku").asText()).isEqualTo(bought.sku());
        assertThat(placedLine.get("productName").asText()).isEqualTo(bought.productName());
        assertThat(placedLine.get("optionLabel").asText()).isEqualTo("Ruby Red / 50ml");
        assertThat(placedLine.get("optionColor").asText()).isEqualTo("Ruby Red");
        assertThat(placedLine.get("optionSize").asText()).isEqualTo("50ml");
        assertThat(placedLine.get("swatchHex").asText()).isEqualTo("#9B111E");
        assertThat(placedLine.get("imageUrl").asText()).isEqualTo(PHOTO);

        renameEverything(bought);

        // Staff's order screen.
        JsonNode line = okStaff(get("/api/v1/orders/" + order.orderId())).get("items").get(0);
        assertThat(line.get("sku").asText()).isEqualTo(bought.sku());
        assertThat(line.get("productName").asText()).isEqualTo(bought.productName());
        assertThat(line.get("variantName").asText()).isEqualTo(bought.productName() + " – Ruby Red / 50ml");
        assertThat(line.get("optionLabel").asText()).isEqualTo("Ruby Red / 50ml");
        assertThat(line.get("optionColor").asText()).isEqualTo("Ruby Red");
        assertThat(line.get("optionSize").asText()).isEqualTo("50ml");
        assertThat(line.get("swatchHex").asText()).isEqualTo("#9B111E");
        assertThat(line.get("imageUrl").asText()).as("the photo it was ordered with, though staff deleted it since")
                .isEqualTo(PHOTO);

        // The shopper's own order page reads the same snapshot.
        OrderItemDto mine = customerLine(order.orderId());
        assertThat(mine.sku()).isEqualTo(bought.sku());
        assertThat(mine.productName()).isEqualTo(bought.productName());
        assertThat(mine.optionLabel()).isEqualTo("Ruby Red / 50ml");
        assertThat(mine.optionColor()).isEqualTo("Ruby Red");
        assertThat(mine.imageUrl()).isEqualTo(PHOTO);

        // And so does the receipt, built when the payment is confirmed, i.e. after the rename.
        reset(emailSender);
        claimAndApprove(order);
        List<String> sent = textsSentTo(order.email());
        assertThat(sent).as("one receipt").hasSize(1);
        assertThat(sent.get(0))
                .contains(bought.productName() + " (Ruby Red / 50ml) × 1")
                .doesNotContain("Velvet Lip Colour")
                .doesNotContain("Crimson");
    }

    @Test
    void anOrderPlacedBeforeSnapshotsExistedFallsBackToTheLiveVariantWithoutErrors() throws Exception {
        Bought bought = newGeneratedLookingVariant();
        Placed order = place(bought);
        // Exactly what a row from before V62 looks like: every snapshot column null.
        jdbc.update("UPDATE customer_order_item SET product_name_snapshot = NULL, variant_name_snapshot = NULL,"
                + " sku_snapshot = NULL, option_label_snapshot = NULL, option_color_snapshot = NULL,"
                + " option_size_snapshot = NULL, swatch_hex_snapshot = NULL, image_url_snapshot = NULL"
                + " WHERE order_id = ?", UUID.fromString(order.orderId()));
        renameEverything(bought);

        JsonNode line = okStaff(get("/api/v1/orders/" + order.orderId())).get("items").get(0);
        assertThat(line.get("sku").asText()).isEqualTo("RENAMED-" + bought.sku());
        assertThat(line.get("productName").asText()).isEqualTo("Velvet Lip Colour " + bought.sku());
        assertThat(line.get("optionLabel").asText()).isEqualTo("Crimson / 30ml");
        assertThat(line.get("optionColor").asText()).isEqualTo("Crimson");
        assertThat(line.get("optionSize").asText()).isEqualTo("30ml");
        assertThat(line.get("swatchHex").asText()).isEqualTo("#DC143C");
        assertThat(line.get("imageUrl").isNull()).as("nothing frozen, and nothing is looked up live").isTrue();

        OrderItemDto mine = customerLine(order.orderId());
        assertThat(mine.sku()).isEqualTo("RENAMED-" + bought.sku());
        assertThat(mine.imageUrl()).isNull();

        reset(emailSender);
        claimAndApprove(order);
        assertThat(textsSentTo(order.email()).get(0)).contains("Velvet Lip Colour " + bought.sku() + " (Crimson / 30ml) × 1");
    }
}
