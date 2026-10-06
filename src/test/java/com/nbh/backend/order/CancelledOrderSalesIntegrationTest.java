package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.repository.ProductSaleEventRepository;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Found in the 2026-10-06 go-live QA: "top sellers" showed 7 lipsticks sold against 5 on orders that were still
 * standing. Units are counted as sold when a payment lands, and an order staff cancel afterwards (the customer changed
 * their mind, the goods were damaged) was never taken back out, so cancelled orders inflated the top-sellers list, the
 * "popular" ranking and the restock forecast, which is built on the same figures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CancelledOrderSalesIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductSaleEventRepository saleEvents;

    private String staffToken;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private record Product(String productId, String variantId) {}

    private Product newProduct() throws Exception {
        String body = "{\"name\":\"Cancel Sales Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":50,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return new Product(productId, variantId);
    }

    /** Places a pickup order for {@code qty} and, when {@code pay}, confirms its payment. Returns the order id. */
    private String order(Product product, int qty, boolean pay) throws Exception {
        String session = UUID.randomUUID().toString();
        MvcResult cart = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + product.variantId() + "\",\"qty\":" + qty + "}")).andReturn();
        assertThat(cart.getResponse().getStatus()).as(cart.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult checkout = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"Test Customer\",\"guestEmail\":\"" + TestContacts.email() + "\",\"guestPhone\":\""
                        + TestContacts.phone() + "\",\"fulfilmentType\":\"PICKUP\",\"deliveryAddress\":\"Junction — Stalls D5\"}")).andReturn();
        assertThat(checkout.getResponse().getStatus()).as(checkout.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode placed = json.readTree(checkout.getResponse().getContentAsString());
        if (pay) {
            okStaff(post("/api/v1/payments/" + placed.get("paymentRecordId").asText() + "/confirm-manual")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reference\":\"SALE" + UUID.randomUUID().toString().substring(0, 8) + "\",\"method\":\"BANK\"}"));
        }
        return placed.get("order").get("id").asText();
    }

    private BigDecimal unitsSold(Product product) {
        return saleEvents.totalUnitsSold(UUID.fromString(product.productId()));
    }

    @Test
    void cancellingAPaidOrderTakesItsUnitsBackOutOfTheSalesFigures() throws Exception {
        Product product = newProduct();
        String kept = order(product, 2, true);
        String cancelled = order(product, 3, true);
        assertThat(unitsSold(product)).as("both paid orders count as sold").isEqualByComparingTo("5");

        okStaff(post("/api/v1/orders/" + cancelled + "/cancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"customer changed their mind\"}"));

        assertThat(unitsSold(product)).as("only the order that stands still counts").isEqualByComparingTo("2");
        assertThat(kept).isNotBlank();
    }

    @Test
    void cancellingAnOrderThatWasNeverPaidIsStillFineAndChangesNothing() throws Exception {
        Product product = newProduct();
        order(product, 1, true);
        String unpaid = order(product, 4, false);
        assertThat(unitsSold(product)).isEqualByComparingTo("1");

        okStaff(post("/api/v1/orders/" + unpaid + "/cancel"));

        assertThat(unitsSold(product)).isEqualByComparingTo("1");
    }
}
