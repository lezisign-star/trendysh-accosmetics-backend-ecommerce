package com.nbh.backend.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * "Five wrong guesses burn the code" must hold when the guesses arrive at the same moment. The limit
 * counts attempts by reading the row, adding one and writing it back; unless those steps are serialised,
 * guesses made together all read the same count and overwrite one another's increment, so a burst of
 * parallel guesses is barely counted and a 6-digit code can be brute-forced. (The same pattern guards the
 * customer and staff password-reset codes.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentTrackingCodeGuessTest {

    private static final Pattern OTP_CODE = Pattern.compile("\\n(\\d{6})\\n");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CustomerOrderRepository orders;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmailSender emailSender;

    @Test
    void aBurstOfParallelWrongGuessesIsCountedExactlyAndBurnsTheCode() throws Exception {
        String email = "burst-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("BRST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setGuestEmail(email);
        order.setGuestPhone("0711" + String.format("%06d", (int) (Math.random() * 1_000_000)));
        order.setCustomerName("Burst Test");
        order.setFulfilmentType(FulfilmentType.PICKUP);
        order.setStatus(OrderStatus.PAID);
        order.setSubtotal(new BigDecimal("100.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("100.00"));
        orders.save(order);

        mvc.perform(post("/api/v1/orders/track/email/code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn();
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(email), anyString(), body.capture(), anyString());
        Matcher m = OTP_CODE.matcher(body.getValue());
        assertThat(m.find()).isTrue();
        String code = m.group(1);
        String wrong = code.equals("000000") ? "111111" : "000000";

        int guesses = 40;
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < guesses; i++) {
            Callable<String> guess = () -> {
                go.await();
                return mvc.perform(post("/api/v1/orders/track/email/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + wrong + "\"}"))
                        .andReturn().getResponse().getContentAsString();
            };
            results.add(pool.submit(guess));
        }
        go.countDown();
        int counted = 0;
        for (Future<String> result : results) {
            if (result.get().contains("Incorrect code")) {
                counted++;
            }
        }
        pool.shutdown();

        // Exactly five guesses were weighed against the code, however many arrived at once: the first four
        // are answered "Incorrect code. N left", the fifth "Too many incorrect attempts", and every later
        // one is turned away without being weighed at all.
        assertThat(counted).as("guesses answered with an attempts-left count").isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT attempts FROM order_tracking_code WHERE email = ?", Integer.class, email))
                .as("attempts recorded").isEqualTo(5);
        // ...and the code is now dead: even the right one is refused.
        assertThat(mvc.perform(post("/api/v1/orders/track/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}")).andReturn().getResponse().getStatus())
                .isEqualTo(422);
    }
}
