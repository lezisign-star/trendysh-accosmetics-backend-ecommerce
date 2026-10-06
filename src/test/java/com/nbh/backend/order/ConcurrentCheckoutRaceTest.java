package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Found in the 2026-10-06 live QA: a double-click on "Place order" (or a retry) made two orders from one basket, holding
 * the stock twice and using up two of the shopper's three open-order slots. Checkout locks the basket so the second
 * submit sees "already checked out", but the basket had already been read, without the lock, a step earlier in the same
 * transaction (when its prices were refreshed), and a lock does not refresh what was read before it: the second submit
 * went on with a stale "still open" basket.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentCheckoutRaceTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        request.header("Authorization", "Bearer " + jwtService.generateAccessToken(admin));
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String newVariantWithStock() throws Exception {
        String body = "{\"name\":\"Race Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":50,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return variantId;
    }

    @Test
    void severalSubmitsOfOneBasketAtTheSameMomentMakeExactlyOneOrderAndOneStockHold() throws Exception {
        String variantId = newVariantWithStock();
        String session = UUID.randomUUID().toString();
        MvcResult added = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":2}")).andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(200);

        String email = TestContacts.email();
        String body = """
                {"customerName":"Double Click","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"PICKUP",
                 "deliveryAddress":"Junction — Stalls D5"}
                """.formatted(email, TestContacts.phone());

        int submits = 6;
        ExecutorService pool = Executors.newFixedThreadPool(submits);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < submits; i++) {
            Callable<Integer> submit = () -> {
                go.await();
                return mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(submit));
        }
        go.countDown();
        int placed = 0;
        int refused = 0;
        for (Future<Integer> result : results) {
            int status = result.get();
            if (status == 200) placed++;
            else if (status == 409) refused++;
        }
        pool.shutdown();

        assertThat(placed).as("submits that made an order").isEqualTo(1);
        assertThat(refused).as("submits told the basket was already checked out").isEqualTo(submits - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE guest_email = ?", Long.class, email))
                .as("orders for this shopper").isEqualTo(1L);
        // Two of the item were wanted, once: the shelf is held for one order, not six.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM stock_reservation WHERE variant_id = ?::uuid", Long.class, variantId))
                .as("stock holds on the product").isEqualTo(1L);
    }
}
